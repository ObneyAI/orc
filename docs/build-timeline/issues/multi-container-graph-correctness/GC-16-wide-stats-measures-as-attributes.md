# GC-16 — Wide-stats sources: model measures as attributes, not per-row entities

> **⛔ SUPERSEDED (2026-07-01).** This deterministic measure-explosion guardrail was built, failed three rounds of live verification (couldn't distinguish pseo from O\*NET; regressed clean sources; earnings-carriage was model-variance-dependent), and was REVERTED. Replaced by the graph-grounded modeling + order-independent reformation initiative: see [`../graph-grounded-modeling/README.md`](../graph-grounded-modeling/README.md), the [PRD](../../prd/graph-grounded-modeling-and-reformation.md), and [ADR 0001](../../../adr/0001-mention-log-rederived-view.md). Kept for the root-cause evidence only.

**Type:** HITL (modeling quality) · **Blocked by:** — · **Status:** root-caused (live evidence), scoped, ready to build.

## The bug (root-caused, not assumed)

The full 5-source graph-B build (`eb12_graph_b_central_evolver.clj`) CRASHES during embedding because of **pseo** (the PSEO program-earnings Excel — each row = institution × program × cohort × geo × industry, with ~90 stat columns). Live evidence:

- Phase-marker trace: `model-extract DONE drafts=7948` (from a 2-container/2-window SLIVER) → embed starts → JVM dies hard with NO Java OOM (native memory exhaustion: DJL/PyTorch + ColBERT off-heap on ~6–8k bloated concepts).
- Draft dump (2/2): ~6000 drafts split EXACTLY 1987/1987/1987 across `institution` / `program` / an **earnings-outcome** kind — i.e. **3 concepts per row**.
- The smoking gun is the **model-spec the Model `:llm` authored** for pseo:
  ```clojure
  {:type "earnings-outcome"
   :uri-keying-fields ["institution" "cipcode" "Degree\nAward\nLevel"
                       "grad_cohort" "geo_level" "industry"]   ; 6 breakdown dims
   :grain-strategy ":breakdown-as-entity" :breakdown-key "grad_cohort"}
  ```
  The Model modeled EARNINGS as a separate breakdown-ENTITY keyed on six breakdown dimensions → one concept per (program × cohort × geo × industry) row. Earnings should be native-number `:attributes` on the program (the domain goal: "carry any numeric OUTCOME in :attributes as native numbers").
- Second, separable defect: **attribute bloat** — every concept carries the ENTIRE ~90-column row, each key DUPLICATED as keyword AND string (`:institution` + `"institution"`), plus a malformed `:Degree\nAward\nLevel` key (the dual-key carry leaks both forms + the whole row).

The other 4 sources are CLEAN (3-source build = 3896 concepts, 0 dangling). pseo's WIDE DENORMALIZED STATS shape is what defeats the Model. The fix is GENERAL — wide stats tables (earnings, census, labor stats, survey aggregates) are common.

## The fix (decided with the user: prompt guidance + structural guardrail; modeling-only scope)

Three coordinated changes, all DOMAIN-AGNOSTIC (name NO pseo column):

1. **Model prompt — measures-vs-entities guidance.** In the DT3 model-node prompt (`discovery_tree.clj` ~278–371, surfaced via `model_subbehavior.clj` `model-prompt`), add guidance: in a WIDE source with many numeric/aggregate columns (statistics, percentiles, earnings, counts, gaps), those columns are MEASURES of an entity — carried as `:attributes` — NEVER minted as a breakdown-entity. Model only the real entity nouns the goal cares about. (The existing grain-strategy / canonical-row-filter guidance stays; this adds the measure distinction it lacks.)

2. **Structural guardrail (deterministic, no LLM) — the backstop the prompt can't guarantee.** After the Model authors the model-spec, a deterministic check detects a **measure-explosion entity-type** and REPAIRS it BEFORE extraction (so the ~6000 concepts are never minted):
   - DETECT, generally: an entity-type whose `:grain-strategy :breakdown-as-entity` keys identity on columns the SOURCE PROFILE classifies as MEASURE/non-identifying (numeric aggregates), i.e. it would mint ≈ one concept per row (no collapse). Use the survey profile's identifying-keys vs measure columns — NOT a hardcoded column list.
   - REPAIR: drop the measure-entity-type and re-route its measure columns to `:attributes` on the PARENT entity it points to (via the model-spec edge, e.g. `outcome_for_program → educational-program`), taking the parent's canonical-row-filter grain. Malli-validate the repaired model-spec.
   - This is a structural guardrail (Malli + skeleton logic), the user's stated posture: trust the prompt AND add a structural check — no phrase matching, no hardcoded fields.

3. **Attribute selectivity.** The Extract AUTHOR / dual-key carry must put only the entity's keying-field VALUES + declared measures into `:attributes` — NOT the whole row, and NOT both keyword AND string forms of every key (fix the dual-key leak so the final draft attributes are single-form + selective).

## Acceptance criteria

- [ ] **pseo extraction is sane:** at 2/2 it produces institutions + programs + fields (deduped at entity grain) with earnings as native-number `:attributes` on the program — NOT ~2000 per-row earnings-outcome concepts. Concept count drops by ~an order of magnitude. Verified on the REAL pseo Excel.
- [ ] **No attribute bloat:** a program/institution draft carries only its keying values + relevant measures, single-form keys (no keyword+string duplicates, no malformed newline key).
- [ ] **Structural guardrail (TDD red→green, pure):** given a synthetic model-spec with a measure-explosion entity-type (breakdown-keyed on profile-measure columns) + a profile, the guardrail detects + repairs it (measures → attributes on the parent); a legitimate breakdown-entity (real identity grain) is left UNTOUCHED. Reverting the guardrail → the measure-entity survives (RED). Names no domain field.
- [ ] **The full 5-source build COMPLETES** (no crash), 0 dangling, and earnings/wage-bearing concepts > 0 (the financial/outcome data B needs to compete with A2). The gate can answer an earnings CQ.
- [ ] **General, not pseo-special:** the fix names no pseo column; the guardrail + prompt are driven by the profile/model-spec at runtime. The other 4 sources' graphs are unchanged (3-source build still 3896/0-dangling).
- [ ] Recursive-only RLM untouched; ontology brick gate green; 0 orphan this-repo JVMs after every live run.

## Disciplines

The 13 Core Disciplines apply VERBATIM (see the GC-13 handoff). Load-bearing here: **#1** root-cause not assume (the model-spec evidence is captured — extend it); **#2/#9** adversarial QUALITY (a smaller graph that's still wrong is a FAIL — verify earnings actually land as queryable attributes on the right program); **#4/#5** no false-green / no silent fold that drops data (the repaired measures must be PRESENT as attributes, not lost); **#6** TDD red→green on the pure guardrail; **#8** re-orchestrate not rewrite (extend the Model prompt + add a skeleton guardrail step; do not fork extraction); **#10** deterministic-skeleton ≠ LLM-free (verify BOTH the prompt-guided modeling AND the structural guardrail); **no hardcoded phrase/column matching** (profile-driven, Malli-structural); JVM hygiene.
