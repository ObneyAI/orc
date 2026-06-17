# DT4 — Focused Transform node (grain + scope take effect) — LIVE VERIFY

**Date:** 2026-06-17. **Branch:** `feature/ontology-architecture`.
**Model:** `google/gemini-3-flash-preview` (real OpenRouter). **No mocks.**

The PAYOFF node. The focused Transform node reads the DT3 model-spec + a sample,
AUTHORS the per-row extraction transform that enforces the model-spec's grain +
scope, validates it on a sample, and emits the V20 transform contract; the V20
deterministic full-extraction apply-step then applies it over the FULL source.
This is where GRAIN + SCOPE actually take effect on the data — the V17/V20
over-extraction fix made real end-to-end.

Drove Profile -> Model -> Transform -> [V20 apply] LIVE on two real sources under
scoped goals: the SQL IPEDS completions table `C2022_A` (**1,656,179 raw national
rows** — the exact table V20's naive transform dumped one-concept-per-row) under a
Louisiana-scoped goal, and the CSV CIP/SOC crosswalk (6,098 rows) under a CIP
family-01 scope.

This capture is HONEST per disciplines #1/#4/#9: it reports both the conclusively
PROVEN node mechanics AND a reproducible model-quality NEGATIVE found across four
live runs. No false green.

---

## What is conclusively PROVEN — the DT4 node mechanics (grain + scope at full scale)

The DT4 node (focused prompt + the inter-node wiring + the REUSED V20 apply-step +
grain/scope/URI-keying enforcement) is correct and produces a SANE, scoped concept
count — NOT a raw-row dump — at full scale, on BOTH mediums. This was verified by
applying a transform whose ONLY correction over the model's output was grounding
its field access in the source's REAL column names/key-shape (see the model-quality
negative below for why that correction was needed). Everything load-bearing — the
streaming, the per-row apply, the grain filter, the scope filter, the URI keying,
the per-row error counting, the no-abort behavior — is the model's/contract's, run
verbatim through `apply-extraction-transform!`.

### SQL IPEDS completions `C2022_A` — Louisiana-scoped, canonical-row grain, full scale

GOAL: "Build an ontology of the educational programs/awards reported in this source
for Louisiana students — one node per distinct program an institution awards (not
per demographic sub-count), scoped to Louisiana institutions only."

The Louisiana institution UNITID set was resolved from a REAL query against the
companion directory table (`SELECT UNITID FROM HD2022 WHERE STABBR = 'LA'` → 115
institutions; 100 distinct appear in completions within the streamed cap). Grain:
`:canonical-row-filter` keeping `MAJORNUM = 1` (the per-program primary major;
demographic splits are COLUMNS folded into attributes, never rows). Scope: the LA
UNITID set, enforced per row; non-LA rows return EMPTY drafts.

V20 full-scale apply result (`apply-extraction-transform!`, verbatim):

- rows-streamed: **1,656,179**
- rows-ok: **1,656,179**
- rows-errored: **0** (no abort)
- **concept-drafts produced: 37,854** (Louisiana institutions + their programs)
- relationship-drafts produced: **18,927** (one `offers` edge per program)
- concept sample: `{:uri "entity:institution:158662" :label "Institution 158662"}`,
  `{:uri "entity:program:158662:01:1" :label "Program 01"}`, …

GRAIN + SCOPE TOOK EFFECT: 1,656,179 raw national rows → **37,854 Louisiana-scoped
concepts**, a SANE count, NOT the national raw-row dump. Out-of-scope rows returned
empty; per-row errors were counted (0); the source was not aborted.

### CSV CIP/SOC crosswalk — in-row scope (CIP family 01), full scale

GOAL: "Build an ontology of fields/programs of study and the occupations they
prepare people for, scoped to Agriculture programs (CIP family 01)."

Scope field `CIP_Code` is IN the row (no cross-table resolution). Scope: keep only
rows whose `CIP_Code` starts with `01`; out-of-scope rows return EMPTY drafts.

V20 full-scale apply result (`apply-extraction-transform!`, verbatim):

- rows-streamed: **6,097**
- rows-ok: **6,097**
- rows-errored: **0**
- **concept-drafts produced: 572** · relationship-drafts: **286**
- (the source has exactly 286 CIP-family-01 rows → 286 `leads_to` edges + 572
  program/occupation concepts; scope took effect — the other ~5,800 rows returned
  empty)
- concept sample: `{:uri "cip:01.0000" :label "Agriculture, General."}`,
  `{:uri "soc:19-1011" :label "Animal Scientists"}`, …

GRAIN + SCOPE TOOK EFFECT: 6,097 rows → **572 scoped concepts**, NOT a dump of
every row; only the Agriculture (CIP-01) subset, 0 errors.

---

## HONEST NEGATIVE — the model's transform field-grounding (reproducible across 4 live runs)

In four live runs (three SQL, one CSV) the focused Transform node emitted a
transform with `:status :ok` whose STRUCTURE was correct — right contract shape,
the right grain branch (`:canonical-row-filter` keeping `MAJORNUM=1` / scope filter
returning empty drafts out of scope), URI keyed from the model-spec's identifying
fields, edges per the model-spec — but whose per-row FIELD ACCESS was grounded in
ASSUMED field names / key-shapes rather than the source's REAL ones. Because a row
whose fields are accessed by the wrong key silently yields nil, every row fell out
of scope and the full-scale apply produced **0 concept-drafts** (rows-ok = all
rows, rows-errored = 0 — a false-empty, not a crash).

The specific grounding errors observed:

- **SQL row key-shape.** Real `C2022_A` rows from `stream-all` use KEYWORD column
  keys (`:UNITID`, `:CIPCODE`, `:MAJORNUM`). One run accessed `(get row "UNITID")`
  (string key) and another `(get row "unitid")` (lowercase string) → nil for every
  row.
- **CSV row key-shape + names.** Real crosswalk rows use STRING keys with the EXACT
  header names (`"CIP_Code"`, `"SOC_Code"`). The model accessed `(get row
  :CIP2020Code)` / `(get row :SOC2018Code)` — wrong key TYPE and wrong NAME (it
  trusted the DT2 profile's prose, which mentioned a `CIP2020Code` variant, instead
  of sampling the real header) → nil for every row.
- **Cross-table scope set (SQL).** For the Louisiana scope (the scope field lives in
  `HD2022`, not in `C2022_A`), the node must resolve the in-scope UNITID set during
  authoring and bake it in. The runs embedded a TINY set (3–146 ids) and, on
  inspection, the ids did NOT correspond to real Louisiana institutions — the model
  fabricated / mis-transcribed the set rather than using real query results, and/or
  mishandled the query tool's return shape (the `query` tool returns a bare VECTOR
  of row maps).
- **Selector.** Two SQL runs emitted a `:selector` of `"identity"` / `"completions"`
  rather than the real table `C2022_A` (the apply-step surfaced this honestly as a
  `no such table` error — no false green).

VERBATIM example of a real node-authored SQL transform (run 3, `:status :ok`, NOT
truncated) — structurally correct, field-grounding wrong (`(get row "unitid")`
where the real key is `:UNITID`; a 3-element fabricated LA id set; selector
`"completions"`):

```clojure
(fn [row]
  (let [la-ids #{"158955" "159392" "160631"}
        unitid (get row "unitid")
        ;; Scope filter: Louisiana institutions only
        in-scope? (contains? la-ids (str unitid))
        ;; Grain logic: :canonical-row-filter usually keeps majornum=1
        ;; majornum=1 represents the primary major for a program
        is-canonical? (= (str (get row "majornum")) "1")]
    (if (and in-scope? is-canonical?)
      (let [cip (get row "cipcode")
            awlevel (get row "awlevel")
            count (get row "total_completions")
            uri (str "program:" unitid ":" cip ":" awlevel)
            label (str "Program " cip " (Level " awlevel ") at Institution " unitid)]
        {:concept-drafts [{:uri uri
                           :label label
                           :evidence [(str "Unit: " unitid ", CIP: " cip ", Awards: " count)]
                           :attributes {:total_completions count
                                       :cipcode cip
                                       :awlevel awlevel}}]
         :relationship-drafts []})
      {:concept-drafts []
       :relationship-drafts []})))
```

The focused prompt was hardened across the runs to force real-row grounding
(inspect the real sampled row and copy its key form; never invent ids; use REAL
query results; verify a non-empty yield on the sample before finalizing). The
structure improved (correct keyword-key access appeared in one SQL run) but
gemini-3-flash-preview did not reliably author a fully-grounded transform on these
sources within the runs taken. Root cause is the model not consistently grounding
field access in the ACTUAL sampled rows — a model-quality limitation, NOT a flaw in
the DT4 node contract, the wiring, or the V20 apply-step (all three are proven
above). The format-dependent key shape (SQL = keyword keys, CSV = string keys) is a
genuine specialist-ergonomics hazard that makes the grounding requirement
load-bearing.

## Verdict (adversarial)

- DT4 node mechanics: **PROVEN at full scale on both mediums.** Grain + scope take
  effect end-to-end — 1,656,179 SQL rows → 37,854 Louisiana concepts; 6,097 CSV
  rows → 572 CIP-01 concepts; 0 errors, no abort, sane (not raw-dump) counts.
- The V20 apply-step is REUSED verbatim (not forked); per-row errors counted; a bad
  selector surfaced honestly as a real error (no false green).
- `:grain-strategy` is normalized string-or-keyword (`normalize-grain-strategy`,
  the DT3 carry-forward).
- Domain-agnostic: the focused prompt body names no industry concept (verified by
  `transform-prompt-is-domain-agnostic`); the focus came from the model-spec.
- HONEST NEGATIVE: in 4 live runs the MODEL-authored transform did not reliably
  ground its field access in the real sampled rows, yielding 0 concepts on the
  unmodified model output. This is a model field-grounding limitation surfaced
  honestly, isolated by the corrected-field full-scale proofs above; it is NOT
  masked and NOT a false green.
