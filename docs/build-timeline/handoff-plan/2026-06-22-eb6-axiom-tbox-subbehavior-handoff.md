# EB6 Handoff — Axiom/TBox subbehavior (emit real TBox axioms; close :axioms-skipped)

Fresh-context brief for EB6 (`docs/build-timeline/issues/evolutionary-builder/EB6-axiom-tbox-subbehavior.md`).
Crafted post-merge from EB3's REAL `candidate-axioms` contract + the REAL S07
command surface. Work DIRECTLY on `feature/ontology-architecture` (NOT a worktree).
DO NOT COMMIT/PUSH — leave staged; the orchestrator `/inspect-orc`s then commits
locally. Implement via `/tdd`.

## The goal
The **Axiom/TBox** subbehavior as a delegatable sheet (mint — a genuine gap). It
turns EB3's `candidate-axioms` + the extracted graph into REAL TBox axioms emitted
through the **S07 commands** (events land — read back via the projection), closing
the still-open `:axioms-skipped` drop (candidate axioms produced but never emitted).
Domain-agnostic: axioms come from the runtime model, never hardcoded vocab.

## Read first
1. `components/ontology/src/ai/obney/orc/ontology/core/model_subbehavior.clj` (~L150) — EB6's INPUT: `candidate-axioms-schema` = `{:axioms [{:kind <any> :rationale <any> …}]}` (tolerant maps; EB6 reads them TOLERANTLY and maps `:kind` → the right S07 command). Also the sheet/registry pattern + the EB4/EB5 sheets (`extract_subbehavior.clj`, `reconcile_subbehavior.clj`) for the `:code`-orchestration + C1 + `orc-service.interface` (boundary-correct) pattern.
2. `components/ontology/src/ai/obney/orc/ontology/interface/schemas.clj` (~L1521–1545) — the S07 command param schemas (the REAL command surface):
   - `:ontology/assert-disjointness` `{:ontology-id :class-uris [≥2 strings]}`
   - `:ontology/assert-property-characteristic` `{:ontology-id :predicate :characteristic [:functional|:transitive|:symmetric] :inverse-of?}`
   - `:ontology/assert-sub-property` `{:ontology-id :sub-predicate :super-predicate}`
   - `:ontology/assert-chain-axiom` `{:ontology-id :chain [≥2 preds] :derived-predicate}`
3. The S07 axiom commands' handlers (grep `defcommand :ontology/assert-` in `components/ontology/src/.../core/commands.clj` or `lints`/axioms ns) + the **read-back**: `rm/get-axioms` (exposed via `interface/get-axioms`; ~interface.clj:336). Assert events LANDED by reading it back (discipline 7), not a return value.
4. The V07 coercion precedent (grep `V07` / a deterministic candidate→command coercion) — EB6's coercion is the same shape.
5. Locate the current axiom DROP (grep `:axioms-skipped` / where candidate-axioms are produced but not emitted — the discovery/skeleton path) — EB6 closes it. Confirm axioms now LAND where they previously skipped.

## What EB6 must build
A delegatable sheet `ontology-axiom-tbox/...@v1` (mirror EB4/EB5 registry pattern).
Reconcile is deterministic-leaning: prefer a `:code` coercion node that maps each
`candidate-axioms` entry by `:kind` → the matching S07 command, grounding class/
predicate URIs against the REAL extracted graph (class-uris must be real entity-type
URIs; don't assert disjointness over URIs not in the graph). Reads
`[:ontology-id :candidate-axioms]` (+ the graph via `:ontology-id`) → writes an
emission report `[:axioms-emitted :axioms-unsupported …]`.

**Coverage + the honest-gap rule (load-bearing — this is closing a SILENT-DROP bug):**
- Emit the kinds S07 supports: **disjointness, property-characteristic (functional/
  transitive/symmetric), sub-property, chain**.
- `subClassOf`: determine whether it's represented via the existing concept
  `:broader` (SKOS) — if so, route there; else it's a gap.
- `domain` / `range` / `closure` (and `subClassOf` if no path): there is **no S07
  command** today. For any candidate `:kind` with no emission path, SURFACE it in
  `:axioms-unsupported` with the rationale — do NOT silently skip (silent skipping
  is exactly the `:axioms-skipped` bug). If the slice scope allows, MINT the missing
  command (it's a mint slice); otherwise surface it as a tracked gap. Report which.
- Any `:llm` reasoning (e.g. deriving a closure axiom from "source enumerates a set
  completely") writes `:reasoning` FIRST (#13); no hardcoded phrase matching (#7/#12).

## C1 / contract
`:code` node → native return crosses `:delegate` parsed. If an `:llm` node emits a
map, give it a STRUCTURED `[:map …]` schema (EB3 lesson). Read axioms back via
`get-axioms` to prove they LANDED.

## Do NOT
- Hardcode any vertical axiom vocab (axioms come from `candidate-axioms`/the graph). Silently skip an unsupported kind (surface it). Fork S07 commands (reuse). Touch EB1–EB5 or unrelated files. Commit/push. Create a worktree.

## Prototype (WORTH — do first)
From a real model-spec's `candidate-axioms` + a landed graph, prove **disjointness +
a property-characteristic + subClass** (via `:broader` or a minted command) are
DERIVED + LANDED via S07 (read back via `get-axioms`). Capture it.

## Verify
- `/tdd` red→green; sheet built + registered; delegated candidate-axioms + ontology-id; axioms LAND (read back via `get-axioms` — discipline 7); unsupported kinds surfaced (not skipped).
- **LIVE verify:** real axioms emitted + landed for a real source (run EB3→EB4 to get real candidate-axioms + a real graph, or feed a captured real model-spec). Capture verbatim (`docs/build-timeline/live-verify/EB6-axiom-tbox.md`): the emitted axioms read back from `get-axioms`, + any `:axioms-unsupported`.
- **Gate hygiene:** if the EB6 test is fast + hermetic (axiom emission is command-only, no ColBERT/LLM) it stays in the brick gate; if its live verify drives extraction/embedding/ColBERT (multi-second), the LIVE test goes to `development/ontology-integration/ai/obney/orc/ontology/` (on-demand). Decide per measured runtime; report which.
- Green under BOTH `clj -M:poly test brick:ontology` AND `:dev:test` of the EB6 ns.
- JVM hygiene: bounded runs; 0 orphan THIS-repo JVMs after (exclude sibling worktrees — kill only your own by PID).

## Report back (raw data)
Sheet + node design; the prototype result (disjointness + characteristic + subClass landed, read back); the LIVE capture (emitted axioms via `get-axioms` verbatim + `:axioms-unsupported`); whether you MINTED a missing command (subClass/domain/range/closure) or surfaced it as a gap + why; which lane the test went in; dual-runner totals; "0 orphan THIS-repo JVMs"; every file changed by path; honest negatives. DO NOT COMMIT/PUSH. Binding Core Disciplines block 1–13 (in the EB6 issue) in force verbatim.
