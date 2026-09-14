# RR-24 implementation handoff: behaviour mints carry iteration provenance

## Goal

A behaviour minted by a researcher campaign records which iteration and which attempt
minted it, so a confident first-attempt mint is distinguishable from a fourth-attempt
fallback; a replayed iteration neither re-mints nor forces a second corpus reindex; and
mint identity stays stable so a repeated mint resolves to one concept.

## Read first

1. `AGENTS.md`, `docs/ORC-PRINCIPLES.md`.
2. `docs/issues/rr-durable/RR-24-behaviour-mints-carry-iteration-provenance.md`.
3. Dossier: finding S4 (~line 49: "`mint-behavior!` carries `:minted-by-sheet-id/:minted-by-tick-id`
   but no iteration; `force-rebuild!` reindexes per mint unconditionally… a replayed iteration
   re-mints"), decision 3 (~line 213: claim-before-effect with content-derived identities that
   exclude attempt/order), and R8 (identity generated inside the durable step) in
   `docs/build-timeline/grill-sessions/rr-durable-self-learning-dossier.md`.
4. Spec excerpts below; `specs/orc-service.allium` `EffectClaim`, `CampaignIteration`,
   `EffectsAreClaimedBeforeTheyHappen`, `StableLogicalActionsPreventDuplicateWork`;
   `specs/ontology.allium` `MintNovelBehavior`, `ConceptProvenance`.
5. RR-7 handoff (`docs/build-timeline/handoff-plan/RR7-claim-epoch-fence-HANDOFF.md`) —
   the landed claim/identity API this slice consumes.

## Verified mechanism map (read from the landed code)

- **Identity (RR-7, landed):** `components/orc-service/src/ai/obney/orc/orc_service/core/researcher_effects.clj`
  `logical-action-identity` = sha-256 over `[tick node iteration-index generated-code-hash kind
  target arguments]` (attempt and order deliberately absent); `attempt-identity` = sha-256 over
  `[logical-action-identity ownership-epoch attempt-ordinal]`.
- **Claim before effect (RR-7, landed):** the sandbox's `mint-behavior!`
  (`core/rlm_sandbox.clj` ~690–800) computes the logical action identity, the attempt
  identity, checks `completed-researcher-effects` for an already-completed action, claims
  the effect (`claim-researcher-effect!` → `:rlm/researcher-effect-claimed` carrying
  `:iteration-index :logical-action-identity :attempt-identity :attempt-ordinal
  :ownership-epoch :kind`), then dispatches `:ontology/mint-behavioral-subtree` with
  `:logical-action-identity`, `:attempt-identity` and `:researcher-iteration` (the
  iteration index). A CAS conflict on the logical-action tag is recovered through
  `ontology/get-behavior-mint-by-logical-action` (the winning durable mint is reused).
- **The mint command** (`components/ontology/src/ai/obney/orc/ontology/core/commands.clj`
  ~1847–1925): `target-id` = name-UUID of `"mint:" name ":" parent-behavior` (identity is
  stable by construction); emits `:ontology/behavioral-subtree-minted` (tags
  `[:behavioral-subtree-minted target-id]` + `[:researcher-logical-action …]`; body carries
  `:minted-by-sheet-id :minted-by-tick-id :logical-action-identity :attempt-identity
  :researcher-iteration`) and `:ontology/tree-description-updated`; CAS
  `no-events?` on the logical-action tag. **Missing today:** the minted event does not carry
  `:attempt-ordinal` or `:ownership-epoch` — the attempt is only recoverable by hashing, so a
  reader cannot tell a first attempt from a fallback. Schemas: `interface/schemas.clj`
  ~825–835 (command) and ~1620–1632 (event).
- **Reindex on mint:** `components/ontology/src/ai/obney/orc/ontology/core/todo_processors.clj`
  `on-behavioral-subtree-minted-force-rebuild` (~983) calls `force-rebuild!` (~949) on every
  `:ontology/behavioral-subtree-minted` event, unconditionally; the companion
  `:tree-description-updated` also feeds the threshold-gated `on-description-updated-maybe-reindex`
  (~964). Because a replayed mint hits the logical-action CAS and emits NO second minted event,
  a replay produces no second forced rebuild by construction — but nothing proves it, and
  a processor-level at-least-once re-delivery of the SAME minted event would call
  `force-rebuild!` again (check whether the reindex state makes that a no-op; if not, make the
  forced rebuild idempotent per minted event id).
- **Seams:** `components/orc-service/test/ai/obney/orc/orc_service/r05c_mint_behavior_sandbox_test.clj`
  (sandbox `mint-behavior!` through a real command context),
  `researcher_effect_claim_test.clj` (identities, claims, fences),
  `components/ontology/test/ai/obney/orc/ontology/reindex_processor_test.clj`,
  `deterministic_ontology_e2e_test.clj` `det-e2e-090-immediate-mint-reindex` (counts
  `colbert-ops/create-index!` calls through `index-stub`), `det-e2e-081-seed-bootstrap`
  (stable derived mint ids).

## Exact behavioral change

1. **Explicit attempt provenance on the mint.** `:ontology/mint-behavioral-subtree` accepts
   and `:ontology/behavioral-subtree-minted` records `:attempt-ordinal` (non-negative int)
   and `:ownership-epoch` (int ≥ 1) alongside the existing `:researcher-iteration`,
   `:attempt-identity` and `:logical-action-identity`, whenever a logical action identity is
   present; the sandbox's `mint-behavior!` passes them from its claim. Both optional in the
   schemas (pre-RR-24 replay stays valid) but always present on new researcher mints.
2. **Provenance is readable.** A public ontology query/accessor over the minted event
   answers `{:iteration-index n :attempt-ordinal n :ownership-epoch n :first-attempt? bool}`
   for a mint, where `:first-attempt?` is `attempt-ordinal = 0` on the campaign's first
   ownership epoch — the evidentiary distinction the issue names. No ranking change in this
   slice; consumers may read it later.
3. **A replayed iteration forces no second reindex.** Prove through the real path: the same
   logical action minted twice (second dispatch conflicts on the CAS and recovers the winning
   mint) yields exactly one minted event, one `:tree-description-updated`, one derived
   `target-id`, and exactly one forced `colbert-ops/create-index!` call. Make the forced
   rebuild idempotent per minted event if processor re-delivery can double it (a durable
   marker on the reindex state, not a process-local set).
4. **Mint identity stays stable** — assert the derived `target-id` is identical across the
   replay and across attempts with the same name and parent.

## TDD cycle list (contract namespace supplied by the orchestrator; RED confirmed)

`components/orc-service/test/ai/obney/orc/orc_service/rr24_mint_iteration_provenance_test.clj` — **5 tests / 30 assertions, 4 failures, 0 errors.** RED for real: `a-researcher-mint-records-its-iteration-and-attempt` (the minted event carries no `:attempt-ordinal` / `:ownership-epoch`) and `provenance-distinguishes-a-first-attempt-mint-from-a-late-fallback` (`ontology/behavior-mint-provenance` does not exist). Already GREEN before implementation (findings, keep as guards): `a-replayed-iteration-neither-re-mints-nor-forces-a-second-reindex` — RR-7's logical-action CAS plus `get-behavior-mint-by-logical-action` recovery already yield one minted event, one description and ONE forced rebuild on replay; `mint-identity-remains-stable-across-attempts-and-replays`; and the structural `entity-fields.*` case. The fixture drives the real sandbox with `:durable-source-required? true`, a recording `:claim-researcher-effect!` and a no-op `:complete-researcher-effect!` (the effect-claim command has its own RR-7 suite), and stubs only `colbert-ops/create-index!` with the colbert interface loaded so the forced-reindex dispatch runs. Cycle 3 in the list below therefore reduces to keeping that test green and, if processor re-delivery of ONE minted event could double the forced rebuild, making it idempotent per minted event id with a RED test of your own.

1. RED→GREEN `a-researcher-mint-records-its-iteration-and-attempt` (schemas + command + sandbox pass-through).
2. RED→GREEN `provenance-distinguishes-a-first-attempt-mint-from-a-late-fallback` (accessor).
3. RED→GREEN `a-replayed-iteration-neither-re-mints-nor-forces-a-second-reindex` (CAS recovery + one create-index).
4. RED→GREEN `mint-identity-remains-stable-across-attempts-and-replays`.
5. Structural (already green — findings): `effect-claim-and-iteration-records-carry-their-declared-fields`
   for `entity-fields.EffectClaim` and `entity-fields.CampaignIteration` (RR-7/RR-5 schemas).
6. Run: the contract namespace, `r05c-mint-behavior-sandbox-test`, `researcher-effect-claim-test`,
   `reindex-processor-test`, `deterministic-ontology-e2e-test`, `rr10-*`/`bounded-campaign-operations-test`
   (mint effects under recovery), then the two solo ontology graphs; allium at baseline;
   `git diff --check`; orphan check.

Harness: `run-focused.sh` (local Grain pins), one JVM at a time, foreground waits; `es/read`
returns a reducible; never edit `specs/*.allium`.

## Spec excerpts (verbatim)

```
entity EffectClaim {
    campaign: Campaign
    iteration: CampaignIteration
    logical_action_identity: String
    attempt_identity: String
    attempt_ordinal: Integer
    ownership_epoch: Integer
    kind: EffectKind
    status: EffectClaimStatus
    claimed_at: Timestamp
    resolved_at: Timestamp?
    ...
}

rule MintNovelBehavior {
    when: NovelBehaviorConfirmed(tree_class, behavior_body, supporting_evidence)
    requires: supporting_evidence.count > 0
    ensures: BehavioralSubtreeMinted(tree_class: tree_class, body: behavior_body,
                                     supporting_evidence: supporting_evidence)
}

value ConceptProvenance {
    kind: ConceptOriginKind
    source_reference: String?
    created_by: String?
    trace_identifier: String?
}
```

## Allium obligation reconciliation

`allium plan specs/orc-service.allium` names `entity-fields.EffectClaim` and
`entity-fields.CampaignIteration` — both structural, already enforced by the RR-7 claim
event schema and the RR-5 iteration-record schema; the contract test's structural case is
expected green before implementation (a finding). Report `2 obligations, 2 covered, 0
uncovered` with the contract namespace's final numbers and every mock/stub/TODO.

## Do NOT touch

`specs/*.allium`; RR-25/RR-26; the identity functions' preimages (attempt/order stay
excluded from the logical identity); the Grain checkout and pins; other worktrees; existing
generated tests.

## Report back

Per-cycle RED/GREEN; files changed and boundaries exercised; the reindex idempotency
decision and its proof; every changed pre-existing assertion with justification; anything
unverified.
