# DT8 — CQ-driven loop + focused recovery — live verify

Date: 2026-06-17 · Branch: `feature/ontology-architecture` · Model for LLM nodes:
`google/gemini-3-flash-preview` (not exercised here — see F3 note).

## What was verified

The tree-owned adaptive CQ loop (`cq-driven-loop!`) + the focused single-node
recovery (`focused-node-recovery!`) filling the DT1 branch stubs. The two HARD
acceptance behaviors were proven over REAL infrastructure:

(a) a failing CQ triggers a FOCUSED re-extract that then PASSES the re-gate;
(b) a genuinely-unanswerable CQ TERMINATES the loop honestly (no spin, no
    false-green).

## Real-infrastructure scope (what was REAL)

- REAL Grain in-memory event store + command-processor (commands → schema-
  validated events → projections; no bare appends).
- REAL `skeleton/build!` — the intact deterministic skeleton
  (normalize → dedup → validate → embed → index → S15 CQ gate), invoked
  UNCHANGED as the loop's deterministic sub-call.
- REAL `ontology/evaluate-cqs!` per-CQ verdicts via the S15 Layer-1 deterministic
  structural path (`Is there an X concept?` resolved against the concepts
  projection — NO LLM judge, so the verdicts are real AND reproducible; the
  judge-fn is wired to THROW if called, proving Layer-1 ran).
- REAL `compile-discovery-source!` landing REAL concept events (the same compile/
  event spine the LLM-authored transform feeds into).

## Scenario A — failing CQ → focused re-extract → re-gate PASSES

Spec CQ: `Is there an alpha concept?`. Graph seeded with an unrelated `seed`
concept (non-empty graph, CQ still fails). Exit-criterion pass-rate 1.0.

```
{:initial-status :failed-cq,
 :final-status :complete,
 :termination :cq-gate-passed,
 :iterations 1,
 :graph-health {:pass-rate 1.0 :unknown-rate 0.0 :fail-rate 0.0
                :total-cqs 1 :pass-count 1 :layer-counts {:layer-1-structural 1}
                :judge-share 0.0 ...},
 :alpha-landed? true}
```

Initial build! gated `:failed-cq` (pass-rate 0.0 < 1.0). The focused re-extract
landed a REAL `alpha:1` concept via the real compile path; the loop re-ran build!
to RE-GATE; the S15 Layer-1 verdict flipped to `:pass`; `build!` returned
`:complete`. ONE iteration. PASS.

## Scenario B — genuinely-unanswerable CQ → honest termination

Spec CQ: `Is there a phantom concept?`. Graph has a `real` concept; nothing for
`phantom`. The focused re-extract supplies NOTHING (the sources genuinely lack the
data), `:max-iterations 5`.

```
{:initial-status :failed-cq,
 :final-status :failed-cq,
 :termination :all-remaining-unanswerable,
 :unanswerable ["Is there a phantom concept?"],
 :reextract-calls 1}
```

The loop attempted the focused re-extract ONCE, observed the graph did not grow
toward the CQ, marked the CQ unanswerable, and terminated honestly:
`:status :failed-cq` (NOT a fake `:complete`) carrying the surfaced unanswerable
CQ. It did NOT spin (1 re-extract, not 5). PASS — V17 honest-negative ethos held.

## Domain-agnosticism

The unanswerable decision is judged by S15 against the RUNTIME CQs + the graph
growth signal — NO domain rule, NO hardcoded phrase list, NO vertical knowledge
(Discipline #12). The CQ text comes from the runtime spec; the loop names no
domain.

## F3 honesty (what was NOT exercised live, and why)

F3 (DT-followups) is the per-node ~235s Phase-2 LLM-emit-tree timeout on the
LLM-AUTHORED transform nodes. This live verify deliberately exercised the loop
over the REAL deterministic build!+CQ+compile spine and supplied the focused
re-extract's drafts through the REAL compile/event path WITHOUT invoking the
F3-prone LLM transform-authoring session. So the ONE thing NOT proven live here
is the LLM AUTHORING a NEW transform inside `focused-reextract!` end-to-end
against a real source within the per-node timeout — exactly the DT10-gating F3
gap already logged. The loop LOGIC (inspect failing CQs → focused re-extract →
re-gate over real build! → terminate on pass/unanswerable/budget) is proven:
- deterministically (`dt8-cq-loop-recovery-test`, 10 tests / the four backbone
  behaviors + branch deciders + failed-re-extract honesty), and
- over real Grain + real build! + real S15 + real compile here.

When F3 is fixed (raise the per-node Phase-2 tick timeout, or have the focused
nodes avoid emit-tree), the same loop drives the LLM-authored re-extract with no
code change — the re-extract seam is the unchanged transform-node run path.

## JVM hygiene

Pre-run orphan check: 0. Harness wrapped in `future` + `(deref f 240000)` +
`(System/exit 0)`. Post-run orphan check: 0. No spinning JVM left.
```
ps -eo pid,etime,command | grep Desktop/Code/orc/ | grep -v orc-main | grep java
→ 0 ORPHAN JVMS — CLEAN
```
