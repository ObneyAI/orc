# Recursive campaigns are checkpointed by default

Checkpointed execution was introduced as an opt-in mode. Every durable
per-iteration record it produces — completed iterations, claimed effects, resume
and yield evidence — exists only when a node sets the option. The self-learning
loop's documented configuration does not set it.

## Considered options

- **Keep checkpointing opt-in** — rejected. The loop is meant to analyse its own
  behavior, and self-analysis can only read durable events. An opt-in durability
  layer that the loop never enables produces no evidence for the mechanism it
  was built to serve. It also splits the engine into two execution paths whose
  divergence is invisible until someone flips the flag in production.
- **Remove the non-checkpointed path** — rejected. Existing consumers depend on
  current behavior, and a campaign that never yields has no need of the
  machinery.
- **Checkpoint by default, with explicit opt-out** — chosen. Recursive
  campaigns are checkpointed unless a node opts out.

## Consequences

- Every recursive campaign produces per-iteration durable evidence, so the
  self-learning loop observes iterations rather than one outcome per campaign.
- Defects that previously affected only opt-in users become default-path
  defects, and are treated as blockers rather than as known limitations: the
  recovery scan must recognise campaign frontiers and run unprompted; the
  call budget must survive a restart; usage must not be counted more than once
  per quantum.
- Write amplification must be fixed before the default changes. Carrying the
  full iteration history in every checkpoint is quadratic in iterations, which
  is acceptable for an opt-in mode and not for a default.
- Compatibility is preserved by opt-out rather than by opt-in, so the
  specification's compatibility obligation is restated in those terms.
