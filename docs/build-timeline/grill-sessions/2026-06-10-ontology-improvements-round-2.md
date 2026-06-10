---
type: grill-session
date: 2026-06-10
session: ontology-improvements-round-2
status: complete
method: grill-with-docs
repo: orc
branch: feature/ontology-architecture
prior-session: 2026-06-10-orc-ontology-architecture
doc-bundle:
  - docs/build-timeline/research/2026-06-10-ontology-improvement-candidates.md (the agenda — 25 candidates)
  - docs/ARCHITECTURE-ONTOLOGY.md
  - docs/build-timeline/grill-sessions/2026-06-10-orc-ontology-architecture.md
research-inputs:
  - development/bench reports (modern RLM tree patterns vs builder sheets)
  - github.com/vectorize-io/hindsight + github.com/safishamsi/graphify (+ area_51 HINDSIGHT_ANALYSIS.md)
  - ontology-engineering course, class 1 (iOD methodology + OWL semantics)
  - ProtegeOWLTutorialP4_v1_3.pdf — distillation still pending (tool outage); expected confirmatory for B3-B6
output: confirmed decision log + 4-tier triage, ready for /to-prd
next: /to-prd → /to-issues; FURTHER GRILLS EXPECTED as user completes additional ontology classes (multiple perspectives)
---

# Grill Session: Ontology Improvements — Round 2

Walks the 25 improvement candidates from the research synthesis to confirmed
decisions + priority tiers. Builds directly on the morning session's frame
(substrate vs applications).

---

## Q&A Log

### Q1 — The A6 fork: what happens to the evolutionary builder?

**Options:** (i) modernize the 5 sheets in place (A1-A5 hand-edits);
(ii) full recursive-RLM rebuild (model designs entire extraction tree);
(iii) hybrid — deterministic skeleton (substrate contracts: parse, dedupe,
validate, emit events, embed, index) stays hand-authored; discovery phases
(structure analysis → entity-type discovery → relationship discovery) become
recursive-RLM.

**Recommended:** (iii) via "Path 2" sequencing — bench-proven patterns
(per-section :map-each, adversarial verify, hierarchical synthesis) become
hand-authored "ontology-discovery" SEED CORPUS content rather than sheet
hand-edits, so the self-improving loop owns builder quality over time.
Guardrails: extraction bench (sources with known-good expected graphs,
concept/relationship precision+recall) as prerequisite gate; old sheets stay
as regression baseline until RLM path beats them; determinism knob (pin a
recorded discovery tree) for consumers needing reproducible builds.

**User answer:** "i agree on the hybrid but i want us to keep in mind some of
this could get seperated ie they may want to use the ontology but not the
self learining bit... Also the rlm/repl-researchers should be using recursive
mode (the terminal mode needs to be retired eventually as recursive is the
end goal of rlm terminal doesnt let us reason about a tree it just hopes the
model got it the first try). To be clear i love integrating the
self-improving loop so our graph builder can get better and better but i dont
want it to be mandatory for the consumer and they can still use it 'as a
database' without having to use the self-improving full pipeline."

**Decision:** Hybrid (iii), Path 2, with: (1) self-improving integration
strictly opt-in — builder runs fully with the loop off; (2) discovery phases
recursive-only; **terminal RLM mode now explicitly slated for eventual
retirement** (direction strengthened from "preserved for backward compat").

### Q2 — B1: Competency questions as first-class build artifacts

**Recommended:** optional `:competency-questions` on
build-ontology-from-sources; two touchpoints — build-time scoping (CQs
injected into discovery context) and evaluation-time acceptance test
(per-CQ: hybrid-search + traversal assembles evidence, LLM judge scores
answerability → `{:answerable? :confidence :evidence-uris :gaps}` events).
Persistent-contract semantics: CQs stored WITH the ontology (events tagged to
ontology-id) so subsequent grow cycles re-evaluate the standing CQ set
automatically. CQ pass-rate over time = the ontology's health metric.

**User answer:** "yes definitely! This sounds brilliant"

**Decision:** Adopted, both touchpoints, persistent-contract semantics.

### Q3 — B5: Formality ceiling

**Recommended:** lightweight stays the substrate ceiling — adopt formal
axioms **as data + lint rules, never as an embedded reasoning engine**:
- Disjointness stored as axioms → dedup hard KEEP-guard + type-error lint
  (B4 lands here)
- Property characteristics (functional/transitive/symmetric/inverse) as
  data + lints + traversal hints (transitive predicates followable in BFS)
- No DL reasoner, no OWA inference, no per-ontology formality knob
- TTL/OWL export remains the heavyweight bridge (and should export the
  stored axioms — today it captures none)
- ARCHITECTURE doc gains an explicit "not restriction-based inference;
  auto-classification is embedding/rerank-based" entry under what the
  substrate is NOT

**User answer:** "yes i agree"

**Decision:** Adopted as recommended.

### Q4 — C2/B8: Evidence + freshness on concepts (with a critical correction)

**Initial recommendation:** two tiers — Tier 1 deterministic evidence
tracking in compare-to-existing (evidence-count, source refs,
last-reinforced-at, contradiction markers; always on, free); Tier 2
LLM-driven consolidation (freshness trends, refine-not-overwrite definition
evolution, contradiction-resolution proposals) — which I initially framed as
gated behind the self-improving flags.

**User correction (verbatim, load-bearing):** "The self-improving loop i'm
referencing is the r-inject with the repl-researcher nodes with rlm and
recursive modes on and classify bit on... The ontology element should not
avoid using llm nodes because of what was said earlier, it absolutely should
use llms/repl-researcher rlm/recursive and reasoning in order to arrive at
our evolving ontology mechanisms do not avoid those just avoid the r-inject
bit (self evolving the success/failure ontology within the repl researchers
as that turns on constant judges, evolution of the living description,
classification, reranker etc that the user may not want to automatically add
the cost overhead for but still want to use the repl-researchers inside the
evolutionary ontology)"

**Corrected model (confirmed):**
- Using LLMs/repl-researchers (recursive RLM) inside the ontology mechanism
  = just using ORC. Discovery, definition refinement, dedup verdicts,
  contradiction resolution are legitimately LLM-driven substrate/builder
  functionality with their own budget/cadence knobs. NEVER gated behind the
  self-improving loop.
- The opt-in bundle is precisely the R-Inject layer on repl-researcher
  nodes: `:auto-classify?` → classification + reranker + corpus prepend +
  judges + living-description evolution + consolidation of success/failure
  descriptions. OFF: builder runs fully, just doesn't learn across runs.
  ON: the builder gets better and better.
- Tier 1 unchanged. Tier 2 = ontology-mechanism functionality available to
  all consumers (budget-knobbed), NOT R-Inject-gated. Separate flag decides
  whether the builder's repl-researchers participate in the loop.

**User answer to restatement + Q5 rolled in:** "yes i agree ithink this is good!"

### Q5 — Dedup cascade

**Decision (confirmed with Q4's restatement):** full tiered cascade in the
rebuild's deterministic skeleton — exact-norm → entropy gate → MinHash/LSH
blocking → Jaro-Winkler → focused LLM merge/keep verdict ONLY in the
ambiguity band, with the explicit "differ in any number/negation/entity →
KEEP" guard (C3, substrate-unconditional per Q4's correction) + type-based
blocking promoted from ontology_exploration to all paths + Q3's disjointness
KEEP-guard as the first hard gate. C1 co-occurrence: RECORD pair-count
events from day one; defer USING them for disambiguation until data
accumulates.

### Q6 — Final triage of remaining candidates

**User answer:** "yes i confirm"

| Candidate | Tier |
|---|---|
| C9 per-source caps before RRF | **NOW** (independent retrieval fix) |
| B2 goal/scope build params | WITH-REBUILD |
| B3 roles-vs-classes heuristic + lint | WITH-REBUILD |
| B4 disjointness (axioms-as-data + guards) | WITH-REBUILD (settled Q3) |
| B6 naming-convention lint | WITH-REBUILD |
| B7 statement-level provenance (evidence quotes) | WITH-REBUILD |
| C5 edge confidence EXTRACTED/INFERRED/AMBIGUOUS | WITH-REBUILD (tagging + queue read-model); NEXT (HITL UI — ops-workshop ratification is the natural home) |
| C8 incremental maintenance | WITH-REBUILD (source content-hashing); NEXT (affected-set BFS re-embed/redefine) |
| C10 causal/typed relations everywhere | WITH-REBUILD (promote ontology_exploration's causal pass to all paths + traversal support) |
| C6 temporal validity | WITH-REBUILD (optional valid-from/until fields + supersession marker); NEXT (NL time-window retrieval) |
| C7 communities (Leiden) + summaries | **RECORDED** (revisit when a live graph is big enough to hurt) |
| B9 shared mid-level/abstraction reuse | **RECORDED** |

Big rocks already locked: B1 CQs + Tier-1 evidence + dedup cascade + A3/A4
shared libraries → WITH-REBUILD. Tier-2 LLM concept-consolidation → follows
once CQs prove signal. Extraction bench → rebuild's prerequisite gate. Old
sheets → regression baseline.

---

## Decisions Summary

| # | Topic | Decision |
|---|-------|----------|
| 1 | Builder future | Hybrid: hand-authored deterministic skeleton (contracts) + recursive-RLM discovery phases; bench patterns become seed corpus, not hand-edits |
| 2 | RLM mode | Recursive-only for discovery; terminal mode slated for eventual retirement repo-wide |
| 3 | Opt-in boundary | The R-Inject layer (`:auto-classify?` bundle) is THE optional bit; LLM/repl-researcher use inside the ontology mechanism is unconditional (budget-knobbed) |
| 4 | Competency questions | Adopted; build-time scoping + judge-scored evaluation; persistent contract stored with the ontology; CQ pass-rate = health metric |
| 5 | Formality | Lightweight ceiling; axioms-as-lints (disjointness, property characteristics); no reasoner; TTL export is the heavyweight bridge |
| 6 | Evidence/freshness | Tier 1 deterministic (always-on, in compare-to-existing); Tier 2 LLM consolidation (mechanism-level, budget-knobbed, not R-Inject-gated) |
| 7 | Dedup | Full tiered cascade + LLM verdict in ambiguity band + disjointness guard; co-occurrence recorded now, used later |
| 8 | Verification | Extraction bench prerequisite; old sheets as regression baseline; determinism knob for reproducible builds |
| 9 | Triage | NOW: C9. WITH-REBUILD: B2-B4, B6, B7, C5(tag), C6(schema), C8(hash), C10. NEXT: C5(HITL), C6(retrieval), C8(affected-set), Tier-2 consolidation. RECORDED: C7, B9 |

---

## Linked Artifacts

- Agenda/synthesis: `docs/build-timeline/research/2026-06-10-ontology-improvement-candidates.md`
- Frame: `docs/ARCHITECTURE-ONTOLOGY.md`
- Prior session: `2026-06-10-orc-ontology-architecture.md`
- Pending: ProtegeOWL tutorial PDF distillation (fold into the synthesis doc when extraction unblocks)
- **Next:** /to-prd → /to-issues. Additional grill rounds expected as the user
  completes further ontology-engineering classes (multiple perspectives to
  fold in before/while the PRD work proceeds).
