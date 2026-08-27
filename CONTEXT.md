# ORC

Behavior-tree workflow execution engine for LLM-powered workflows, built on Grain event sourcing. This glossary is the ubiquitous language — use these terms verbatim in code, tests, docs, and UI.

## Language

### Retrieval

**Signal**:
One independent ranked list of candidates feeding hybrid search. The three signals are graph, embedding, and ColBERT.
_Avoid_: source, backend, retriever

**Hybrid search**:
Retrieval that fuses the ranked lists of multiple signals into one ranking.
_Avoid_: multi-search, combined search

**ColBERT signal**:
The late-interaction signal in hybrid search. Names the matching method, not a particular model, index format, or runtime.
_Avoid_: RAGatouille, Python signal, PLAID

**Late interaction**:
Matching a query against a candidate token-by-token at query time, instead of comparing one pooled vector per text.
_Avoid_: token search, deep rerank

**MaxSim**:
The late-interaction score: each query token takes its best-matching candidate token, and those best matches are summed.
_Avoid_: similarity score (ambiguous with embedding cosine)

**Encoder checkpoint**:
The trained model that turns text into per-token embeddings for the ColBERT signal.
_Avoid_: the model (ambiguous with workflow LLMs)

**Index artifact**:
The on-disk representation of a ColBERT index. Derived data — always rebuildable from the events that describe its corpus; never the source of truth.
_Avoid_: index files, the .ragatouille directory

**Graceful degradation**:
Hybrid search's contract that an absent or failing ColBERT signal silently narrows the search to the remaining signals, never erroring.
_Avoid_: fallback mode

### Recursive researcher

**Campaign**:
One repl-researcher node execution from first Phase-1 iteration to `final!` — the whole recursive research effort for a single task, however many iterations, yields, or process restarts it spans.
_Avoid_: run, session, researcher invocation

**Iteration**:
One Phase-1 turn within a campaign: generate code, execute it in the sandbox, and record what it produced. The unit the model reasons in and the unit the self-learning loop learns from.
_Avoid_: step, cycle, turn

**Quantum**:
The bounded amount of work a campaign advances before yielding control back to the engine — a whole number of iterations.
_Avoid_: batch, chunk, slice

**Checkpoint**:
The durable record of a campaign's state after a completed iteration. What a campaign resumes from; never a cache.
_Avoid_: snapshot (reserved for published workflow snapshots), save point

**Yield**:
A campaign returning control to the engine between quanta, with its progress already durable. Distinct from finishing.
_Avoid_: pause, suspend

**Frontier**:
The point a campaign resumes at — the node execution awaiting its next quantum.
_Avoid_: cursor, position

**Occurrence**:
One campaign observed as one unit of evidence by the self-learning loop, identified by the execution it ran in. Counting rule: one campaign is one occurrence, however many quanta, resumes, or restarts it took.
_Avoid_: run, assignment, event (an occurrence is not an event count)

**Classification**:
The corpus match decided for a campaign's task before it designs anything, and the patterns prepended to the model from that match. A per-campaign fact, decided once.
_Avoid_: categorization, tagging, routing

**Iteration digest**:
The bounded, durable account of what one iteration did — its code, the tree it emitted, the outcome, and what changed — carried as evidence for the loop. Code is kept; data payload values are reduced to keys and profiles.
_Avoid_: summary, log, trace entry

**Worked pattern**:
The tree shape a class is known to succeed with, recorded per shape and backed by the outcome that proved it. A shape that failed is recorded as such and never displaces one that worked.
_Avoid_: recommended tree, best tree, latest tree

**Resume state**:
What a campaign needs to continue from its frontier — current sandbox values, accumulated usage and timing, remaining budgets, and where it is up to. Mutable, overwritten each quantum.
_Avoid_: checkpoint data, campaign state

**Iteration record**:
The immutable durable fact of what one iteration did. Written once, never rewritten; the evidence stream the self-learning loop reads.
_Avoid_: history entry, checkpoint history

**Abandoned campaign**:
A campaign that stopped without reaching success, failure, or timeout — its frontier was never resumed. Evidence about our infrastructure, never a verdict about the behavior.
_Avoid_: failed campaign (a failure IS a verdict), crashed run

**Recurrence**:
How many times a task of a given class has actually been carried out to a verdict. Counted at outcome, never at intent.
_Avoid_: occurrences count, assignment count

**Shape**:
The structural form of a generated tree, independent of the instructions and code inside it. Two trees that differ only in their generated code share a shape.
_Avoid_: fingerprint (that is the identifier for a shape, not the concept), pattern (a pattern is a shape plus what it proved)

**Winning shape**:
The shape that carried a campaign to its verdict. One per campaign; the unit of convergence.
_Avoid_: final tree, last tree

**Behavior**:
A crystallized, reusable account of how a class of task has been carried out successfully — what it is for, when to avoid it, and the worked pattern that proved it. Evidence the model reasons with, never a pipeline it is forced down.
_Avoid_: template, playbook, macro

**Adopt / Adapt / Specialize / Mint**:
The four moves available to a model shown a reference behavior: use its pattern as-is; modify it for this task; mint a child of it that pins a narrower domain; or mint a fresh behavior for a genuinely novel task.
_Avoid_: reuse, fork, inherit

**Claim**:
A campaign's durable assertion of ownership over one effect before that effect happens, carrying the epoch that made it. The same record that makes an effect's outcome knowable afterwards.
_Avoid_: lock, lease (a lease is granted by the platform; a claim is asserted by the campaign)

**Epoch**:
The monotonically increasing generation of ownership over a frontier. A write carrying a superseded epoch is rejected.
_Avoid_: version, generation, term

**Indeterminate effect**:
An effect that was claimed but has no recorded outcome — it may or may not have happened. Always durably visible, never silently assumed either way.
_Avoid_: failed call, lost call
