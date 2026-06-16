# V17 — Graph B full-scale rebuild (AUTONOMOUS discovery) — LIVE VERIFY

**Date:** 2026-06-16. **Branch:** `feature/ontology-architecture`.
**Model:** `google/gemini-3-flash-preview` (real OpenRouter). **Embeddings:** local all-MiniLM-L6-v2 (DJL, 384-dim). **ColBERT:** real index. **No mocks.**

Ontology-id: `4e0e42fd-f4b9-4e56-97c0-5772956e8508`. Sources (path + format ONLY — contents are the builder's to discover): [:ipeds :crosswalk :onet :wages :pseo].

Budget per source: `{:max-iterations 24, :total-budget-ms 1800000, :max-retries 3}`.

Artifact (loadable by V10/V12): `docs/build-timeline/live-verify/V17-graph-b-full-scale-artifact.edn`.

## Test-result summary (honest — this slice is a TEST of the evolutionary builder)

This was a LIVE autonomous build that doubles as a test of the recursive-RLM
evolutionary builder under the load-bearing rule (NO hardcoded joins / keys /
columns / offsets / LIMITs / crosswalk recipes in the driver). The driver gave
the builder only the 5 sources, the per-format exploration tools, the domain
goal, and a generous budget. What follows is what the builder discovered ON ITS
OWN. A faithfully-reported partial result is the deliverable; nothing below is
patched with hand-holding to manufacture success.

**No-hardcoding rule: HONORED.** The driver's per-source text is the domain goal
only (verbatim below); zero table/column/offset/LIMIT/join/key tokens. Every
cross-source link in the graph is the builder's own discovery. (Caveat reported
in full: the platform-shipped `cross-source-linking` scaffolding that
`run-discovery!` prepends for every discovery task uses illustrative example
URI schemes — `cip:` / `soc:` / `unitid:` — to teach the *general principle* of
minting shareable code-system URIs. That scaffolding is identical for all
discovery tasks, names no table/column/offset/join for THIS corpus, and is not
driver-authored. It teaches the principle; the builder still had to discover
which columns are the codes, that CIP↔SOC is a crosswalk, etc.)

**Skeleton: `:complete`, all 7 stages.** parse→normalize→dedup→validate→embed→
index→exit-criterion in 61 s. Embed 5.8 s; ColBERT index 20.3 s over 427 docs —
**completed, no timeout (V16 scaling held)**; dedup 35 s over 14,196 pairs; LMDB
4 GB held (no MapFull). The scale walls the slice flagged were all cleared.

**Scale: 427 concepts** (soc 227, unitid/institution 100, cip 100), 249
relationships. On the order of V09's 434 — NOT the "thousands" the AC asked for.
HONEST cause: per-source per-call sampling caps (SQL/CSV 100, Excel 500). This
slice ROOT-CAUSED and FIXED the hardest of these (SQL had a 100-row server-side
cap with NO offset path → comprehensive SQL coverage was structurally
impossible; V17 added `:offset` paging to SQL `sample-rows`/`query`, mirroring
CSV/Excel). But the BUILDER did not exploit paging to comprehensiveness on its
own — it took roughly one window per source despite the goal's explicit
"PAGE through it … do not settle for the first window" instruction. So coverage
is a sample, not the full LA program set. Reported as a real autonomous-coverage
miss, not patched with a hardcoded query.

**Cross-source connectivity program↔CIP↔SOC: PARTIAL.** The builder DID discover
the central bridge: `cip:` minted from IPEDS merges with `cip:` from the
crosswalk, and `soc:` from the crosswalk merges with `soc:` from O*NET and wages
— yielding **100 `cipMapsToSoc` edges** that genuinely connect the program side
to the occupation side, plus 100 `offersProgram` (institution→CIP) and 49 O*NET
`relatedTo` edges. BUT: **119 of 249 edges DANGLE** (`every-edge-endpoint-
resolves false`), and **NO complete program→field→occupation multi-hop path
reads back**. Two self-inflicted builder inconsistencies cause this:
  1. **Programs modeled as edges, not nodes.** Unlike V09, the builder minted NO
     `program:` concept — it linked `unitid → offersProgram → cip` directly. So
     there is no program node to anchor the multi-hop walk (program-count = 0).
  2. **The builder referenced CIP/SOC codes in edges that it never minted as
     concepts.** IPEDS minted `cip:` concepts only from the `CIPCodes` taxonomy
     window it sampled (mostly the alphabetical top — `cip:01.*` agriculture),
     but emitted `offersProgram` edges to the CIPs completions actually use
     (`cip:11.0201`, `cip:10.0203`, …): only 1 of 29 program-edge CIP targets
     resolves. Likewise O*NET emitted `relatedTo` edges between 8-digit SOC codes
     (`soc:11-1011.03`) while many soc concepts are 6-digit — so the endpoints
     don't match. V09 avoided this because its prompt HAND-FED a "mint the cip
     for EVERY completions row" rule and a single SOC encoding; V17 did not, and
     the builder did not discover the referential-integrity discipline on its
     own. **This is the headline autonomous-discovery quality miss.**

**Earnings→program bridge: NOT discovered — and for a more basic reason than
V09's key mismatch.** PSEO discovery returned **`:no-output`**: the builder
explored the Earnings sheet, burned ~4 iterations on Excel `sample-rows`/`sheet-
columns` ARITY errors (passed a sheet map instead of a name; passed a 4th arg),
recovered the sheet names, and its rlm-trace CLAIMS it "Minted canonical unitid:
and cip: URIs" — but it never reached a valid `final!` carrying drafts before
exhausting budget, so ZERO earnings concepts landed. (Verified this is a genuine
builder failure, not a harness swallow: `run-discovery!` returned `:no-output`
with empty `:concept-drafts`; `:session-error nil`.) With no earnings concepts
at all, the bridge is impossible by construction. Measured verdict:
`:bridge-discovered? false`, `:earnings-concept-count 0`, `:earnings-edge-count
0`, `:institution-id-overlap-count 0`. The driver named no key and added no
driver-side join. This is an HONEST negative — even more so than V09 (which at
least extracted earnings and left them disjoint by key-encoding); here the
earnings source was not successfully extracted at all.

**Retrievability: degraded vs V09.** Hybrid-search returns labeled hits but
several are off-target (e.g. "registered nurse occupation" surfaces "Medical and
Health Services Managers"/"Funeral Home Managers"; "computer science
engineering" surfaces "Horticultural Science"/"Brewing Science" CIPs). The
agriculture-heavy `cip:01.*` concept set (the alphabetical window IPEDS sampled)
pollutes results. Same embedding model + ColBERT config as A1 (fairness held).

**Harness robustness fix (root-caused mid-run).** The FIRST V17 run CRASHED the
whole 5-source build when O*NET emitted one malformed draft (`{:uri "soc:"
:label nil}`) and `compile-discovery-source!` threw loudly (correct — no silent
drop). For a multi-source autonomous build, one source's bad draft should not
lose the other four sources' committed work nor block capture. Fix: the driver
ISOLATES each source's compile in try/catch — a compile failure is recorded as
THAT source's honest outcome (offending draft captured verbatim) and the build
proceeds. This does NOT weaken validation and does NOT silently drop a bad draft;
the source that emits one lands zero concepts and is flagged. (In the captured
run O*NET happened to succeed, so the isolation was a safety net, not exercised.)

**Bottom line.** The builder autonomously discovered the program↔CIP↔SOC bridge
(the crosswalk's purpose) and merged concepts across four of five sources by
shared canonical id — a real positive. It did NOT achieve comprehensive
coverage, referential integrity (119 dangling edges), a complete multi-hop
read-back, or the earnings→program bridge (PSEO yielded nothing). These are
faithfully reported as the test results; none were patched. Per the slice's own
standard ("a faithfully-reported partial result is a SUCCESS … a fabricated full
result is a failure"), this is the honest partial.

## The no-hardcoding audit — EXACT prompts handed to the builder

The driver's entire per-source 'hand' is the DOMAIN GOAL (identical for every source) prepended to the shipped `default-discovery-prompt`. NO table names, column names/indices, row offsets, LIMIT values, join keys, or crosswalk recipes appear below. (`run-discovery!` further prepends the shipped per-format exploration MECHANICS for a structured source — those name no domain key; they describe how to call the sampling tools.)

### The DOMAIN GOAL (verbatim — the only per-source driver text)

```
DOMAIN GOAL — build a comprehensive, connected ontology over these Louisiana education-and-career sources: the educational programs offered, the fields of study they belong to, the occupations those fields lead to, the institutions that offer them, and the earnings / wage outcomes associated with them.

Cover the Louisiana program set COMPREHENSIVELY. Where the source you are exploring is large, PAGE through it (use the :offset window affordance of the sampling tools) until you have covered the entities the goal asks for — do not settle for the first window. The deterministic transform you design runs over ALL the rows you gather at no extra cost, so retrieve the full relevant set, not a token sample.

Where different sources refer to the same real-world entity, MERGE them by minting the SAME canonical identifier (a stable, shareable id derived from the code system the source itself uses), so a concept this source contributes and a concept another source contributes for the same real thing resolve to ONE node. FIND and USE whatever shared keys or crosswalk information the sources THEMSELVES provide to connect across sources — explore the source to discover what those keys are; they are not given to you.

Carry any numeric OUTCOME a concept has (earnings, wages, employment, growth, tuition, percentiles) in :attributes as native numbers so they stay queryable.

This is ONE source of several that together form the connected graph; mint your concepts so they will link up with the others by shared canonical id.

============================================================

```

### The full assembled prompt per source (verbatim)

#### Source `ipeds`

```
DOMAIN GOAL — build a comprehensive, connected ontology over these Louisiana education-and-career sources: the educational programs offered, the fields of study they belong to, the occupations those fields lead to, the institutions that offer them, and the earnings / wage outcomes associated with them.

Cover the Louisiana program set COMPREHENSIVELY. Where the source you are exploring is large, PAGE through it (use the :offset window affordance of the sampling tools) until you have covered the entities the goal asks for — do not settle for the first window. The deterministic transform you design runs over ALL the rows you gather at no extra cost, so retrieve the full relevant set, not a token sample.

Where different sources refer to the same real-world entity, MERGE them by minting the SAME canonical identifier (a stable, shareable id derived from the code system the source itself uses), so a concept this source contributes and a concept another source contributes for the same real thing resolve to ONE node. FIND and USE whatever shared keys or crosswalk information the sources THEMSELVES provide to connect across sources — explore the source to discover what those keys are; they are not given to you.

Carry any numeric OUTCOME a concept has (earnings, wages, employment, growth, tuition, percentiles) in :attributes as native numbers so they stay queryable.

This is ONE source of several that together form the connected graph; mint your concepts so they will link up with the others by shared canonical id.

============================================================

TASK: Extract an ontology draft (concepts + relationships + axioms) from the supplied source content.

You have a recursive RLM environment. Tools available include:
  - graph-search, neighborhood, get-concept, exists?, absent-in-graph?, filter-by-label-pattern (S19 ontology tools — these query the EXISTING graph so you don't duplicate concepts that already exist)
  - classify-task, classify-behaviors (existing classifier surface — calling (classify-behaviors {:task-signature "<your goal>"}) retrieves patterns that fit your discovery task)
  - emit-tree! (the recursive RLM tree designer — design ONE tree per discovery pass)

FIRST: call (classify-behaviors {:task-signature "ontology discovery from <type> source"}) to see which ontology-discovery patterns the corpus suggests. The retrieved patterns are behavioral subtrees specialized for discovery — choose the one that fits your source's size and shape, adapt it, or design a fresh tree if none fit.

OUTPUT SHAPE (via (final! ...)):
  {:concept-drafts [{:uri <str> :label <str> :description <str> :scope <kw>                      :attributes {<key> <value> ...}                      :evidence [{:source <str> :quote <str>}]} ...]
     — :attributes is an OPTIONAL map of QUERYABLE facts about the concept. Put any NUMERIC OUTCOME or grounding value here (e.g. earnings/wage figures, tuition / net-cost, employment counts, growth rates, percentiles) keyed by a short name, with the value kept as its native type (a NUMBER stays a number — do NOT stringify it). These attributes are how a downstream query reads the outcome back, so a program/occupation concept that has an earnings or wage figure MUST carry it in :attributes (not only in prose).
   :relationship-drafts [{:source-uri <str> :target-uri <str> :predicate <str>                           :confidence-class :extracted                           :evidence [{:source <str> :quote <str>}]} ...]
   :axiom-drafts [{:axiom-type <one of "disjointness" / "property-characteristic" / "sub-property" / "chain"> :body <map> :evidence [{:source <str> :quote <str>}]} ...]
     — axiom :body shapes by :axiom-type:
         "disjointness"            {:class-uris [<concept-uri> <concept-uri> ...]}  (>=2 URIs)
         "property-characteristic" {:predicate <str> :characteristic [<one or more of "functional" "transitive" "symmetric">] :inverse-of <str, optional>}
         "sub-property"            {:sub-predicate <str> :super-predicate <str>}
         "chain"                   {:chain [<predicate> <predicate> ...] (>=2) :derived-predicate <str>}
     Only emit an axiom the source TEXT supports; do NOT invent OWL structure.
   :rlm-trace [<your iteration summaries — what you classified, what tree                you emitted, what failures you recovered from>]}

GROUNDING DISCIPLINE: every :concept-drafts / :relationship-drafts entry MUST carry a verbatim quote in :evidence. Drafts without quotes are dropped during ingest. Do NOT speculate beyond what the source text states.
```

#### Source `crosswalk`

```
DOMAIN GOAL — build a comprehensive, connected ontology over these Louisiana education-and-career sources: the educational programs offered, the fields of study they belong to, the occupations those fields lead to, the institutions that offer them, and the earnings / wage outcomes associated with them.

Cover the Louisiana program set COMPREHENSIVELY. Where the source you are exploring is large, PAGE through it (use the :offset window affordance of the sampling tools) until you have covered the entities the goal asks for — do not settle for the first window. The deterministic transform you design runs over ALL the rows you gather at no extra cost, so retrieve the full relevant set, not a token sample.

Where different sources refer to the same real-world entity, MERGE them by minting the SAME canonical identifier (a stable, shareable id derived from the code system the source itself uses), so a concept this source contributes and a concept another source contributes for the same real thing resolve to ONE node. FIND and USE whatever shared keys or crosswalk information the sources THEMSELVES provide to connect across sources — explore the source to discover what those keys are; they are not given to you.

Carry any numeric OUTCOME a concept has (earnings, wages, employment, growth, tuition, percentiles) in :attributes as native numbers so they stay queryable.

This is ONE source of several that together form the connected graph; mint your concepts so they will link up with the others by shared canonical id.

============================================================

TASK: Extract an ontology draft (concepts + relationships + axioms) from the supplied source content.

You have a recursive RLM environment. Tools available include:
  - graph-search, neighborhood, get-concept, exists?, absent-in-graph?, filter-by-label-pattern (S19 ontology tools — these query the EXISTING graph so you don't duplicate concepts that already exist)
  - classify-task, classify-behaviors (existing classifier surface — calling (classify-behaviors {:task-signature "<your goal>"}) retrieves patterns that fit your discovery task)
  - emit-tree! (the recursive RLM tree designer — design ONE tree per discovery pass)

FIRST: call (classify-behaviors {:task-signature "ontology discovery from <type> source"}) to see which ontology-discovery patterns the corpus suggests. The retrieved patterns are behavioral subtrees specialized for discovery — choose the one that fits your source's size and shape, adapt it, or design a fresh tree if none fit.

OUTPUT SHAPE (via (final! ...)):
  {:concept-drafts [{:uri <str> :label <str> :description <str> :scope <kw>                      :attributes {<key> <value> ...}                      :evidence [{:source <str> :quote <str>}]} ...]
     — :attributes is an OPTIONAL map of QUERYABLE facts about the concept. Put any NUMERIC OUTCOME or grounding value here (e.g. earnings/wage figures, tuition / net-cost, employment counts, growth rates, percentiles) keyed by a short name, with the value kept as its native type (a NUMBER stays a number — do NOT stringify it). These attributes are how a downstream query reads the outcome back, so a program/occupation concept that has an earnings or wage figure MUST carry it in :attributes (not only in prose).
   :relationship-drafts [{:source-uri <str> :target-uri <str> :predicate <str>                           :confidence-class :extracted                           :evidence [{:source <str> :quote <str>}]} ...]
   :axiom-drafts [{:axiom-type <one of "disjointness" / "property-characteristic" / "sub-property" / "chain"> :body <map> :evidence [{:source <str> :quote <str>}]} ...]
     — axiom :body shapes by :axiom-type:
         "disjointness"            {:class-uris [<concept-uri> <concept-uri> ...]}  (>=2 URIs)
         "property-characteristic" {:predicate <str> :characteristic [<one or more of "functional" "transitive" "symmetric">] :inverse-of <str, optional>}
         "sub-property"            {:sub-predicate <str> :super-predicate <str>}
         "chain"                   {:chain [<predicate> <predicate> ...] (>=2) :derived-predicate <str>}
     Only emit an axiom the source TEXT supports; do NOT invent OWL structure.
   :rlm-trace [<your iteration summaries — what you classified, what tree                you emitted, what failures you recovered from>]}

GROUNDING DISCIPLINE: every :concept-drafts / :relationship-drafts entry MUST carry a verbatim quote in :evidence. Drafts without quotes are dropped during ingest. Do NOT speculate beyond what the source text states.
```

#### Source `onet`

```
DOMAIN GOAL — build a comprehensive, connected ontology over these Louisiana education-and-career sources: the educational programs offered, the fields of study they belong to, the occupations those fields lead to, the institutions that offer them, and the earnings / wage outcomes associated with them.

Cover the Louisiana program set COMPREHENSIVELY. Where the source you are exploring is large, PAGE through it (use the :offset window affordance of the sampling tools) until you have covered the entities the goal asks for — do not settle for the first window. The deterministic transform you design runs over ALL the rows you gather at no extra cost, so retrieve the full relevant set, not a token sample.

Where different sources refer to the same real-world entity, MERGE them by minting the SAME canonical identifier (a stable, shareable id derived from the code system the source itself uses), so a concept this source contributes and a concept another source contributes for the same real thing resolve to ONE node. FIND and USE whatever shared keys or crosswalk information the sources THEMSELVES provide to connect across sources — explore the source to discover what those keys are; they are not given to you.

Carry any numeric OUTCOME a concept has (earnings, wages, employment, growth, tuition, percentiles) in :attributes as native numbers so they stay queryable.

This is ONE source of several that together form the connected graph; mint your concepts so they will link up with the others by shared canonical id.

============================================================

TASK: Extract an ontology draft (concepts + relationships + axioms) from the supplied source content.

You have a recursive RLM environment. Tools available include:
  - graph-search, neighborhood, get-concept, exists?, absent-in-graph?, filter-by-label-pattern (S19 ontology tools — these query the EXISTING graph so you don't duplicate concepts that already exist)
  - classify-task, classify-behaviors (existing classifier surface — calling (classify-behaviors {:task-signature "<your goal>"}) retrieves patterns that fit your discovery task)
  - emit-tree! (the recursive RLM tree designer — design ONE tree per discovery pass)

FIRST: call (classify-behaviors {:task-signature "ontology discovery from <type> source"}) to see which ontology-discovery patterns the corpus suggests. The retrieved patterns are behavioral subtrees specialized for discovery — choose the one that fits your source's size and shape, adapt it, or design a fresh tree if none fit.

OUTPUT SHAPE (via (final! ...)):
  {:concept-drafts [{:uri <str> :label <str> :description <str> :scope <kw>                      :attributes {<key> <value> ...}                      :evidence [{:source <str> :quote <str>}]} ...]
     — :attributes is an OPTIONAL map of QUERYABLE facts about the concept. Put any NUMERIC OUTCOME or grounding value here (e.g. earnings/wage figures, tuition / net-cost, employment counts, growth rates, percentiles) keyed by a short name, with the value kept as its native type (a NUMBER stays a number — do NOT stringify it). These attributes are how a downstream query reads the outcome back, so a program/occupation concept that has an earnings or wage figure MUST carry it in :attributes (not only in prose).
   :relationship-drafts [{:source-uri <str> :target-uri <str> :predicate <str>                           :confidence-class :extracted                           :evidence [{:source <str> :quote <str>}]} ...]
   :axiom-drafts [{:axiom-type <one of "disjointness" / "property-characteristic" / "sub-property" / "chain"> :body <map> :evidence [{:source <str> :quote <str>}]} ...]
     — axiom :body shapes by :axiom-type:
         "disjointness"            {:class-uris [<concept-uri> <concept-uri> ...]}  (>=2 URIs)
         "property-characteristic" {:predicate <str> :characteristic [<one or more of "functional" "transitive" "symmetric">] :inverse-of <str, optional>}
         "sub-property"            {:sub-predicate <str> :super-predicate <str>}
         "chain"                   {:chain [<predicate> <predicate> ...] (>=2) :derived-predicate <str>}
     Only emit an axiom the source TEXT supports; do NOT invent OWL structure.
   :rlm-trace [<your iteration summaries — what you classified, what tree                you emitted, what failures you recovered from>]}

GROUNDING DISCIPLINE: every :concept-drafts / :relationship-drafts entry MUST carry a verbatim quote in :evidence. Drafts without quotes are dropped during ingest. Do NOT speculate beyond what the source text states.
```

#### Source `wages`

```
DOMAIN GOAL — build a comprehensive, connected ontology over these Louisiana education-and-career sources: the educational programs offered, the fields of study they belong to, the occupations those fields lead to, the institutions that offer them, and the earnings / wage outcomes associated with them.

Cover the Louisiana program set COMPREHENSIVELY. Where the source you are exploring is large, PAGE through it (use the :offset window affordance of the sampling tools) until you have covered the entities the goal asks for — do not settle for the first window. The deterministic transform you design runs over ALL the rows you gather at no extra cost, so retrieve the full relevant set, not a token sample.

Where different sources refer to the same real-world entity, MERGE them by minting the SAME canonical identifier (a stable, shareable id derived from the code system the source itself uses), so a concept this source contributes and a concept another source contributes for the same real thing resolve to ONE node. FIND and USE whatever shared keys or crosswalk information the sources THEMSELVES provide to connect across sources — explore the source to discover what those keys are; they are not given to you.

Carry any numeric OUTCOME a concept has (earnings, wages, employment, growth, tuition, percentiles) in :attributes as native numbers so they stay queryable.

This is ONE source of several that together form the connected graph; mint your concepts so they will link up with the others by shared canonical id.

============================================================

TASK: Extract an ontology draft (concepts + relationships + axioms) from the supplied source content.

You have a recursive RLM environment. Tools available include:
  - graph-search, neighborhood, get-concept, exists?, absent-in-graph?, filter-by-label-pattern (S19 ontology tools — these query the EXISTING graph so you don't duplicate concepts that already exist)
  - classify-task, classify-behaviors (existing classifier surface — calling (classify-behaviors {:task-signature "<your goal>"}) retrieves patterns that fit your discovery task)
  - emit-tree! (the recursive RLM tree designer — design ONE tree per discovery pass)

FIRST: call (classify-behaviors {:task-signature "ontology discovery from <type> source"}) to see which ontology-discovery patterns the corpus suggests. The retrieved patterns are behavioral subtrees specialized for discovery — choose the one that fits your source's size and shape, adapt it, or design a fresh tree if none fit.

OUTPUT SHAPE (via (final! ...)):
  {:concept-drafts [{:uri <str> :label <str> :description <str> :scope <kw>                      :attributes {<key> <value> ...}                      :evidence [{:source <str> :quote <str>}]} ...]
     — :attributes is an OPTIONAL map of QUERYABLE facts about the concept. Put any NUMERIC OUTCOME or grounding value here (e.g. earnings/wage figures, tuition / net-cost, employment counts, growth rates, percentiles) keyed by a short name, with the value kept as its native type (a NUMBER stays a number — do NOT stringify it). These attributes are how a downstream query reads the outcome back, so a program/occupation concept that has an earnings or wage figure MUST carry it in :attributes (not only in prose).
   :relationship-drafts [{:source-uri <str> :target-uri <str> :predicate <str>                           :confidence-class :extracted                           :evidence [{:source <str> :quote <str>}]} ...]
   :axiom-drafts [{:axiom-type <one of "disjointness" / "property-characteristic" / "sub-property" / "chain"> :body <map> :evidence [{:source <str> :quote <str>}]} ...]
     — axiom :body shapes by :axiom-type:
         "disjointness"            {:class-uris [<concept-uri> <concept-uri> ...]}  (>=2 URIs)
         "property-characteristic" {:predicate <str> :characteristic [<one or more of "functional" "transitive" "symmetric">] :inverse-of <str, optional>}
         "sub-property"            {:sub-predicate <str> :super-predicate <str>}
         "chain"                   {:chain [<predicate> <predicate> ...] (>=2) :derived-predicate <str>}
     Only emit an axiom the source TEXT supports; do NOT invent OWL structure.
   :rlm-trace [<your iteration summaries — what you classified, what tree                you emitted, what failures you recovered from>]}

GROUNDING DISCIPLINE: every :concept-drafts / :relationship-drafts entry MUST carry a verbatim quote in :evidence. Drafts without quotes are dropped during ingest. Do NOT speculate beyond what the source text states.
```

#### Source `pseo`

```
DOMAIN GOAL — build a comprehensive, connected ontology over these Louisiana education-and-career sources: the educational programs offered, the fields of study they belong to, the occupations those fields lead to, the institutions that offer them, and the earnings / wage outcomes associated with them.

Cover the Louisiana program set COMPREHENSIVELY. Where the source you are exploring is large, PAGE through it (use the :offset window affordance of the sampling tools) until you have covered the entities the goal asks for — do not settle for the first window. The deterministic transform you design runs over ALL the rows you gather at no extra cost, so retrieve the full relevant set, not a token sample.

Where different sources refer to the same real-world entity, MERGE them by minting the SAME canonical identifier (a stable, shareable id derived from the code system the source itself uses), so a concept this source contributes and a concept another source contributes for the same real thing resolve to ONE node. FIND and USE whatever shared keys or crosswalk information the sources THEMSELVES provide to connect across sources — explore the source to discover what those keys are; they are not given to you.

Carry any numeric OUTCOME a concept has (earnings, wages, employment, growth, tuition, percentiles) in :attributes as native numbers so they stay queryable.

This is ONE source of several that together form the connected graph; mint your concepts so they will link up with the others by shared canonical id.

============================================================

TASK: Extract an ontology draft (concepts + relationships + axioms) from the supplied source content.

You have a recursive RLM environment. Tools available include:
  - graph-search, neighborhood, get-concept, exists?, absent-in-graph?, filter-by-label-pattern (S19 ontology tools — these query the EXISTING graph so you don't duplicate concepts that already exist)
  - classify-task, classify-behaviors (existing classifier surface — calling (classify-behaviors {:task-signature "<your goal>"}) retrieves patterns that fit your discovery task)
  - emit-tree! (the recursive RLM tree designer — design ONE tree per discovery pass)

FIRST: call (classify-behaviors {:task-signature "ontology discovery from <type> source"}) to see which ontology-discovery patterns the corpus suggests. The retrieved patterns are behavioral subtrees specialized for discovery — choose the one that fits your source's size and shape, adapt it, or design a fresh tree if none fit.

OUTPUT SHAPE (via (final! ...)):
  {:concept-drafts [{:uri <str> :label <str> :description <str> :scope <kw>                      :attributes {<key> <value> ...}                      :evidence [{:source <str> :quote <str>}]} ...]
     — :attributes is an OPTIONAL map of QUERYABLE facts about the concept. Put any NUMERIC OUTCOME or grounding value here (e.g. earnings/wage figures, tuition / net-cost, employment counts, growth rates, percentiles) keyed by a short name, with the value kept as its native type (a NUMBER stays a number — do NOT stringify it). These attributes are how a downstream query reads the outcome back, so a program/occupation concept that has an earnings or wage figure MUST carry it in :attributes (not only in prose).
   :relationship-drafts [{:source-uri <str> :target-uri <str> :predicate <str>                           :confidence-class :extracted                           :evidence [{:source <str> :quote <str>}]} ...]
   :axiom-drafts [{:axiom-type <one of "disjointness" / "property-characteristic" / "sub-property" / "chain"> :body <map> :evidence [{:source <str> :quote <str>}]} ...]
     — axiom :body shapes by :axiom-type:
         "disjointness"            {:class-uris [<concept-uri> <concept-uri> ...]}  (>=2 URIs)
         "property-characteristic" {:predicate <str> :characteristic [<one or more of "functional" "transitive" "symmetric">] :inverse-of <str, optional>}
         "sub-property"            {:sub-predicate <str> :super-predicate <str>}
         "chain"                   {:chain [<predicate> <predicate> ...] (>=2) :derived-predicate <str>}
     Only emit an axiom the source TEXT supports; do NOT invent OWL structure.
   :rlm-trace [<your iteration summaries — what you classified, what tree                you emitted, what failures you recovered from>]}

GROUNDING DISCIPLINE: every :concept-drafts / :relationship-drafts entry MUST carry a verbatim quote in :evidence. Drafts without quotes are dropped during ingest. Do NOT speculate beyond what the source text states.
```

## Per-source ingestion outcome — including the builder's discovery trace (verbatim)

```clojure
[{:emitted-relationships 100,
  :discovery-ms 12315,
  :compile-error nil,
  :offending-draft nil,
  :sample-new-concepts
  [{:uri "unitid:457606",
    :label "My Le's Beauty College",
    :attributes
    {:city "Gretna", :zip "70056", :url "mylebeautycollegellc.com/"}}
   {:uri "cip:01.1105",
    :label "Plant Protection and Integrated Pest Management."}
   {:uri "unitid:160904",
    :label "Xavier University of Louisiana",
    :attributes
    {:city "New Orleans",
     :zip "70125-1098",
     :url "https://www.xula.edu/"}}
   {:uri "unitid:247083",
    :label "University of Louisiana-System Administration",
    :attributes
    {:city "Baton Rouge", :zip "70802", :url "www.ulsystem.edu/"}}
   {:uri "cip:01.8107",
    :label "Veterinary Microbiology and Immunobiology."}
   {:uri "cip:01.8106", :label "Veterinary Infectious Diseases."}
   {:uri "unitid:160649",
    :label "Southern University at Shreveport",
    :attributes
    {:city "Shreveport", :zip "71107", :url "www.susla.edu/"}}
   {:uri "unitid:436650",
    :label "Medical Training College",
    :attributes
    {:city "Baton Rouge", :zip "70816-5820", :url "www.mtcbr.com/"}}
   {:uri "unitid:483212",
    :label "Louisiana Delta Community College",
    :attributes
    {:city "Monroe", :zip "71203", :url "https://www.ladelta.edu/"}}
   {:uri "unitid:158662",
    :label "Delgado Community College",
    :attributes
    {:city "New Orleans", :zip "70119", :url "https://www.dcc.edu/"}}
   {:uri "cip:01.06",
    :label "Applied Horticulture and Horticultural Business Services."}
   {:uri "cip:01.1103", :label "Horticultural Science."}],
  :discovery-status :emitted-drafts,
  :source :ipeds,
  :emitted-concepts 200,
  :concepts-after 200,
  :emitted-axioms 0,
  :session-error nil,
  :new-concept-count 200,
  :compiled-provenance
  {:status :ingested,
   :concepts-emitted 200,
   :relationships-emitted 100,
   :axioms-emitted 0,
   :rlm-trace
   ["Filtered HD2022 for Louisiana (FIPS 22); extracted institutions, CIP taxonomy, and offersProgram relations based on completions in C2022_A."]},
  :iteration-reasonings
  ["I am starting by listing the tables in the SQLite database to identify relevant classes (e.g., Institutions, Programs, CIP codes) for the Louisiana education ontology. I will also check for behavioral patterns in the classifier to see if there are optimized workflows for SQL-based ontology discovery. Following the instructions, I am calling the tools directly to explore the schema before designing the extraction."
   "I am exploring the specific tables in the IPEDS SQLite database that match the Louisiana education goal. I've identified `HD2022` (Institutional Characteristics), `IC2022` (Student Charges/Options), `C2022_A` (Completions/Programs by CIP), and `CIPCodes` as primary candidates. I will examine their schemas to find the standard linking keys (UNITID for institutions, CIPCODE for programs) and check for Louisiana-specific filters (e.g., FIPS state codes). This direct sampling approach follows the task instructions to avoid complex `emit-tree!` calls for simple SQL discovery and mapping."
   "I am extracting institutional and program data specifically for Louisiana as required by the domain goal. I have identified `HD2022` for institutions (filtering by FIPS code 22 for Louisiana), `CIPCodes` for the field-of-study taxonomy, and `C2022_A` for program completions linking institutions to CIP codes. I will use stable URI schemes like `unitid:<id>` and `cip:<code>` to ensure connectivity with other sources. Alternative approaches like processing every institution were rejected to maintain focus on the Louisiana subset."],
  :relationships-after 100,
  :rlm-trace
  ["Filtered HD2022 for Louisiana (FIPS 22); extracted institutions, CIP taxonomy, and offersProgram relations based on completions in C2022_A."],
  :compile-status :ingested}
 {:emitted-relationships 100,
  :discovery-ms 7906,
  :compile-error nil,
  :offending-draft nil,
  :sample-new-concepts
  [{:uri "soc:19-4012", :label "Agricultural Technicians"}
   {:uri "soc:15-1232", :label "Computer User Support Specialists"}
   {:uri "soc:19-3011", :label "Economists"}
   {:uri "soc:37-3011",
    :label "Landscaping and Groundskeeping Workers"}
   {:uri "soc:45-2093",
    :label "Farmworkers, Farm, Ranch, and Aquacultural Animals"}
   {:uri "soc:25-9021", :label "Farm and Home Management Educators"}
   {:uri "soc:41-4012",
    :label
    "Sales Representatives, Wholesale and Manufacturing, Except Technical and Scientific Products"}
   {:uri "soc:25-1043",
    :label "Forestry and Conservation Science Teachers, Postsecondary"}
   {:uri "soc:45-2011", :label "Agricultural Inspectors"}
   {:uri "soc:49-3011",
    :label "Aircraft Mechanics and Service Technicians"}
   {:uri "soc:45-2021", :label "Animal Breeders"}
   {:uri "soc:19-1013", :label "Soil and Plant Scientists"}],
  :discovery-status :emitted-drafts,
  :source :crosswalk,
  :emitted-concepts 62,
  :concepts-after 230,
  :emitted-axioms 0,
  :session-error nil,
  :new-concept-count 30,
  :compiled-provenance
  {:status :ingested,
   :concepts-emitted 62,
   :relationships-emitted 100,
   :axioms-emitted 0,
   :rlm-trace
   ["Sampled 100 rows from CIP-SOC crosswalk"
    "Minted canonical cip: and soc: URIs for interoperability"
    "Created cipMapsToSoc relationships"]},
  :iteration-reasonings
  ["I am starting by exploring the CSV source using the provided sampling tools. First, I will classify the task to see suggested patterns, and then use `peek-columns` and `sample-rows` to understand the header structure and data format of the CIP-SOC crosswalk. This is necessary to determine the exact column names for CIP codes, SOC codes, and their respective titles, which will allow me to mint stable URIs and create the mapping relationships in the next iteration. I am skipping `emit-tree!` for this discovery phase as directed."
   "In the previous iteration, I identified the CSV structure which maps CIP codes (Classification of Instructional Programs) to SOC codes (Standard Occupational Classification). The columns are \"CIP_Code\", \"CIP_Title\", \"SOC_Code\", and \"SOC_Title\". I will now page through the source to collect a substantial set of mappings (100 rows) to ensure good coverage of the relationships. I am following the worked pattern for crosswalks: minting stable \"cip:xxxx\" and \"soc:xxxx\" URIs and creating \"cipMapsToSoc\" edges between them. This design fulfills the cross-source linking requirement, as other Louisiana datasets will use these same standard codes. I rejected creating intermediate mapping concepts as they would clutter the graph; direct edges are more efficient for a crosswalk."],
  :relationships-after 200,
  :rlm-trace
  ["Sampled 100 rows from CIP-SOC crosswalk"
   "Minted canonical cip: and soc: URIs for interoperability"
   "Created cipMapsToSoc relationships"],
  :compile-status :ingested}
 {:emitted-relationships 49,
  :discovery-ms 12070,
  :compile-error nil,
  :offending-draft nil,
  :sample-new-concepts
  [{:uri "soc:11-3051.02", :label "Geothermal Production Managers"}
   {:uri "soc:11-9199.09", :label "Wind Energy Operations Managers"}
   {:uri "soc:13-1082.00", :label "Project Management Specialists"}
   {:uri "soc:13-1131.00", :label "Fundraisers"}
   {:uri "soc:11-9013.00",
    :label "Farmers, Ranchers, and Other Agricultural Managers"}
   {:uri "soc:11-9039.00",
    :label "Education Administrators, All Other"}
   {:uri "soc:13-2051.00", :label "Financial and Investment Analysts"}
   {:uri "soc:13-1032.00", :label "Insurance Appraisers, Auto Damage"}
   {:uri "soc:11-1011.03", :label "Chief Sustainability Officers"}
   {:uri "soc:11-9072.00",
    :label "Entertainment and Recreation Managers, Except Gambling"}
   {:uri "soc:13-1031.00",
    :label "Claims Adjusters, Examiners, and Investigators"}
   {:uri "soc:11-9031.00",
    :label
    "Education and Childcare Administrators, Preschool and Daycare"}],
  :discovery-status :emitted-drafts,
  :source :onet,
  :emitted-concepts 99,
  :concepts-after 329,
  :emitted-axioms 0,
  :session-error nil,
  :new-concept-count 99,
  :compiled-provenance
  {:status :ingested,
   :concepts-emitted 99,
   :relationships-emitted 49,
   :axioms-emitted 0,
   :rlm-trace
   ["Sampleed 100 occupations from Occupation Data.xlsx"
    "Sampled occupation-to-occupation relationships from Related Occupations.xlsx"
    "Minted canonical soc:<code> URIs for merging across sources."]},
  :iteration-reasonings
  ["I am starting by listing the workbooks in the provided directory to identify which Excel files contain the relevant data for Louisiana education and career outcomes. Since \"db_30_1_excel\" is a directory, I need to see the file structure before I can sample specific sheets. I will also check for existing discovery patterns to align with recommended behaviors. I am skipping `emit-tree!` for now as the instructions prioritize direct tool calls for initial source exploration to save budget."
   "The O*NET source is a massive collection of individual Excel files. To meet the task's domain goal (career outcomes, occupations, and field-of-study connections), \"Occupation Data.xlsx\" is the most critical starting point as it likely contains the canonical SOC codes and occupational titles. I am choosing to sample this specific workbook first to establish the occupational nodes (soc: uris). I will also investigate \"Alternate Titles.xlsx\" which provides synonymy—essential for search and discovery across different sources. Since the instructions strictly forbid `emit-tree!` for sampling and emphasize direct tool calls, I am using `sheet-columns` and `sample-rows` to inspect the structure of the primary occupation table. I considered sampling \"Related Occupations.xlsx\" or \"Knowledge.xlsx\" but prioritized core identity (names and codes) first to ensure the backbone of the career ontology is stable."
   "I have inspected the core occupation definitions in \"Occupation Data.xlsx\" and the alternate titles in \"Alternate Titles.xlsx\". The O*NET-SOC codes serve as stable, shareable URIs (e.g., \"soc:11-1011.00\"). I will now perform a broad sample of the \"Occupation Data\" sheet to capture the primary occupation concepts. I am also interested in \"Related Occupations.xlsx\" to establish connectivity within the career ontology. Since the domain goal includes connecting fields of study to occupations, I will prioritize extracting a substantial set of occupations and their internal relations first. I will use direct tool calls to gather data from \"Occupation Data\" (paged to 100 rows for breadth) and \"Related Occupations\" to see how O*NET links these entities. Finally, I will construct the drafts using the shareable SOC URI pattern."],
  :relationships-after 249,
  :rlm-trace
  ["Sampleed 100 occupations from Occupation Data.xlsx"
   "Sampled occupation-to-occupation relationships from Related Occupations.xlsx"
   "Minted canonical soc:<code> URIs for merging across sources."],
  :compile-status :ingested}
 {:emitted-relationships 0,
  :discovery-ms 12797,
  :compile-error nil,
  :offending-draft nil,
  :sample-new-concepts
  [{:uri "soc:11-9131",
    :label "Postmasters and Mail Superintendents",
    :attributes
    {:median_annual_wage 88670.0,
     :wage_10th_percentile 81806.0,
     :wage_90th_percentile 102606.0,
     :projected_growth_pct 4.35,
     :star_rating 3.0,
     :typical_education_requirement
     "High school diploma or equivalent"}}
   {:uri "soc:17-2072",
    :label "Electronics Engineers, Except Computer",
    :attributes
    {:median_annual_wage 95898.0,
     :wage_10th_percentile 73580.0,
     :wage_90th_percentile 158569.0,
     :projected_growth_pct 11.2,
     :star_rating 4.0,
     :typical_education_requirement "Bachelor's degree"}}
   {:uri "soc:11-9033",
    :label "Education Administrators, Postsecondary",
    :attributes
    {:median_annual_wage 101414.0,
     :wage_10th_percentile 60002.0,
     :wage_90th_percentile 175661.0,
     :projected_growth_pct 6.31,
     :star_rating 5.0,
     :typical_education_requirement "Master's degree"}}
   {:uri "soc:15-1241",
    :label "Computer Network Architects",
    :attributes
    {:median_annual_wage 102422.0,
     :wage_10th_percentile 64302.0,
     :wage_90th_percentile 150485.0,
     :projected_growth_pct 3.55,
     :star_rating 4.0,
     :typical_education_requirement "Bachelor's degree"}}
   {:uri "soc:13-2061",
    :label "Financial Examiners",
    :attributes
    {:median_annual_wage 91547.0,
     :wage_10th_percentile 47567.0,
     :wage_90th_percentile 155570.0,
     :projected_growth_pct 7.43,
     :star_rating 4.0,
     :typical_education_requirement "Bachelor's degree"}}
   {:uri "soc:11-9111",
    :label "Medical and Health Services Managers",
    :attributes
    {:median_annual_wage 100334.0,
     :wage_10th_percentile 62778.0,
     :wage_90th_percentile 167528.0,
     :projected_growth_pct 28.95,
     :star_rating 5.0,
     :typical_education_requirement "Bachelor's degree"}}
   {:uri "soc:15-1212",
    :label "Information Security Analysts",
    :attributes
    {:median_annual_wage 90090.0,
     :wage_10th_percentile 60008.0,
     :wage_90th_percentile 131997.0,
     :projected_growth_pct 27.94,
     :star_rating 5.0,
     :typical_education_requirement "Bachelor's degree"}}
   {:uri "soc:13-1011",
    :label
    "Agents and Business Managers of Artists, Performers, and Athletes",
    :attributes
    {:median_annual_wage 52242.0,
     :wage_10th_percentile 42629.0,
     :wage_90th_percentile 58821.0,
     :projected_growth_pct 11.11,
     :star_rating 3.0,
     :typical_education_requirement "Bachelor's degree"}}
   {:uri "soc:11-3071",
    :label "Transportation, Storage, and Distribution Managers",
    :attributes
    {:median_annual_wage 87714.0,
     :wage_10th_percentile 50247.0,
     :wage_90th_percentile 150987.0,
     :projected_growth_pct 7.46,
     :star_rating 5.0,
     :typical_education_requirement
     "High school diploma or equivalent"}}
   {:uri "soc:11-9081",
    :label "Lodging Managers",
    :attributes
    {:median_annual_wage 59626.0,
     :wage_10th_percentile 31342.0,
     :wage_90th_percentile 102114.0,
     :projected_growth_pct 10.23,
     :star_rating 4.0,
     :typical_education_requirement
     "High school diploma or equivalent"}}
   {:uri "soc:15-1254",
    :label "Web Developers",
    :attributes
    {:median_annual_wage 64822.0,
     :wage_10th_percentile 37419.0,
     :wage_90th_percentile 94065.0,
     :projected_growth_pct 17.71,
     :star_rating 4.0,
     :typical_education_requirement "Bachelor's degree"}}
   {:uri "soc:11-9071",
    :label "Gambling Managers",
    :attributes
    {:median_annual_wage 76553.0,
     :wage_10th_percentile 43436.0,
     :wage_90th_percentile 124122.0,
     :projected_growth_pct -5.0,
     :star_rating 2.0,
     :typical_education_requirement
     "High school diploma or equivalent"}}],
  :discovery-status :emitted-drafts,
  :source :wages,
  :emitted-concepts 100,
  :concepts-after 427,
  :emitted-axioms 0,
  :session-error nil,
  :new-concept-count 98,
  :compiled-provenance
  {:status :ingested,
   :concepts-emitted 100,
   :relationships-emitted 0,
   :axioms-emitted 0,
   :rlm-trace
   ["Sampled 300 rows; deduplicated occupations by soc: code; mapped numerical wage and growth metrics to attributes."]},
  :iteration-reasonings
  ["I am starting by exploring the CSV structure using `peek-columns` and `sample-rows` as instructed. This will allow me to identify the SOC codes for occupations and the corresponding wage/outcome columns. I am also calling `classify-behaviors` to align with established discovery patterns for this source type."
   "The exploration in Iteration 1 successfully identified the CSV structure and provided a representative sample of rows. The source contains Standard Occupational Classification (SOC) codes (`soc_code`), occupation titles (`soc_title`), and various labor market outcomes including education requirements, star ratings, and multiple wage percentiles. \n\nI am choosing a direct transform approach as instructed for sampling tasks, rather than a behavior tree, to stay within budget and minimize overhead. I adopted the `soc:` URI scheme to ensure cross-source linking with other occupation data providers. I rejected creating separate concepts for \"Education Required\" categories, instead treating them as labels/attributes of the occupations to keep the ontology focused on entities (Occupations). I am converting relevant numeric fields (wages, growth percentages, star ratings) to native numbers in the `:attributes` map to ensure they are queryable. I am processing a larger sample (up to 100 rows) to ensure wide coverage of the Louisiana occupation set as requested."
   "The previous iteration failed because `unique-by` is not a standard Clojure core function. I will resolve this by using a combination of `group-by`, `vals`, and `map first` to ensure each SOC code has only one representative concept draft. I am also increasing the row sample size to 300 to capture a broader set of Louisiana occupational data as requested. I have identified the columns from the previous `peek-columns` and `sample-rows` calls, mapping `soc_code` to canonical `soc:` URIs and including wage, growth, and education data in the `:attributes` map."],
  :relationships-after 249,
  :rlm-trace
  ["Sampled 300 rows; deduplicated occupations by soc: code; mapped numerical wage and growth metrics to attributes."],
  :compile-status :ingested}
 {:emitted-relationships 0,
  :discovery-ms 22183,
  :compile-error nil,
  :offending-draft nil,
  :sample-new-concepts [],
  :discovery-status :no-output,
  :source :pseo,
  :emitted-concepts 0,
  :concepts-after 427,
  :emitted-axioms 0,
  :session-error nil,
  :new-concept-count 0,
  :compiled-provenance nil,
  :iteration-reasonings
  ["I am starting by exploring the structure of the Excel workbook `pseo_la.xlsx`. My first objective is to list the available sheets and then inspect the column headers of the most relevant-looking sheets to identify CIP codes, institution IDs (UNITID), and earnings/outcome data. I am also calling `classify-behaviors` to align with established patterns for ontology discovery from structured sources. I will use the excel tools directly as instructed, avoiding `emit-tree!` for these preliminary sampling steps."
   "The previous attempt failed because I passed a map representing a sheet instead of the sheet's name or index to `sheet-columns` and `sample-rows`. I will correct this by using the literal sheet names \"Earnings\" and \"Flows\" discovered in the error message. I'm also choosing to call `classify-behaviors` to align with the prompt's requirement, though I will execute the exploration directly as requested. I'll inspect the \"Earnings\" and \"Flows\" sheets to identify UNITID (institution), CIP (program), and earnings outcomes as required by the domain goal."
   "The exploration of `pseo_la.xlsx` reveals two main sheets: `Earnings` and `Flows`. The `Earnings` sheet provides a rich set of data linking institutions (`institution`), degree levels (`Degree Award Level`), and programs (`cipcode`) to employment outcomes ($1, 5, 10$ year earnings percentiles). The `Flows` sheet provides similar mappings but focuses on employment counts and geographic/industry flows. I will use the identified keys to mint canonical URIs: `unitid:<institution>` for institutions (PSEO uses 6-digit OPEIDs or UNITIDs, here likely the 6-digit ID), `cip:<cipcode>` for programs, and `degree:<level>` or `outcome:<type>` for related concepts. To ensure connectivity with other sources, I will treat the `cipcode` and `institution` codes as stable identifiers. I will process a significant sample of rows from the `Earnings` sheet to capture the variety of programs and institutions across Louisiana, including the associated earnings attributes. I am skipping an `emit-tree!` call in favor of direct processing as the task instructions state it is too slow for simple sampling tasks and I have sufficient data from the preliminary exploration."
   "In the previous iteration, I incorrectly passed a 4th argument (options map) to `sample-rows`. According to the tool definition, the limit and offset should be passed within a single map as the 3rd argument if not using a simple integer. I am sticking to the `Earnings` sheet as it contains the crucial mapping between institutions (`unitid:`), programs (`cip:`), and numeric earnings outcomes required by the domain goal. I will process a representative sample, ensuring I filter out non-data header rows and aggregate rows (where cipcode is \"00\"). I am skipping `emit-tree!` as direct processing of the sampled data is more efficient here."],
  :relationships-after 249,
  :rlm-trace
  ["Sampled Earnings sheet using corrected tool invocation"
   "Minted canonical unitid: and cip: URIs for Louisiana institution-program pairs"
   "Mapped earnings outcomes into relationship attributes for queryability"],
  :compile-status nil}]
```

## Skeleton terminal status

```clojure
{:relationships-count 249,
 :dedup-review-required
 {:count 398,
  :sample
  {:tier :llm-budget-exhausted,
   :verdict :requires-review,
   :reason :budget,
   :detail "LLM budget exhausted"},
  :note
  "elided — repeated budget-exhausted entries (dedup ran with :llm-budget 0)"},
 :stage-timings
 {:parse 0,
  :normalize 1,
  :dedup 35046,
  :validate 0,
  :embed 5781,
  :index 20316,
  :exit-criterion 6},
 :validation-warnings [],
 :graph-health nil,
 :dedup-summary
 {:pairs-evaluated 14196,
  :merges 0,
  :distinct 710,
  :requires-review 398},
 :ontology-id #uuid "4e0e42fd-f4b9-4e56-97c0-5772956e8508",
 :status :complete,
 :spec-absent? true,
 :stages-run
 [:parse :normalize :dedup :validate :embed :index :exit-criterion],
 :concepts-count 427,
 :events-emitted 710}
```

Skeleton wall-clock: 61226 ms.

## Graph-structure stats (V09 schema — feeds V10 diff)

```clojure
{:cross-source-links
 {[:cip "cipMapsToSoc" :soc] 100, [:unitid "offersProgram" :cip] 1},
 :concepts-with-attributes 200,
 :dangling-edge-count 119,
 :cross-source-link-total 101,
 :relationship-count 249,
 :axioms nil,
 :every-edge-endpoint-resolves false,
 :concept-count 427,
 :relationships-by-predicate
 {"offersProgram" 100, "cipMapsToSoc" 100, "relatedTo" 49},
 :sample-dangling-edges
 [{:source-uri "unitid:248527",
   :target-uri "cip:12",
   :predicate "offersProgram"}
  {:source-uri "soc:11-1011.03",
   :target-uri "soc:19-2041.00",
   :predicate "relatedTo"}
  {:source-uri "unitid:159522",
   :target-uri "cip:12.04",
   :predicate "offersProgram"}
  {:source-uri "unitid:437103",
   :target-uri "cip:10.02",
   :predicate "offersProgram"}
  {:source-uri "unitid:158431",
   :target-uri "cip:11.0201",
   :predicate "offersProgram"}
  {:source-uri "unitid:242413",
   :target-uri "cip:12",
   :predicate "offersProgram"}
  {:source-uri "soc:11-1011.03",
   :target-uri "soc:15-1299.09",
   :predicate "relatedTo"}
  {:source-uri "unitid:160667",
   :target-uri "cip:11.09",
   :predicate "offersProgram"}],
 :axiom-count 0,
 :earnings-or-wage-bearing-concepts 97,
 :concepts-by-kind {:soc 227, :unitid 100, :cip 100}}
```

## Connectivity proof (multi-hop path read back from the graph)

```clojure
{:no-complete-chain true,
 :roles-detected
 {:program nil,
  :cip :cip,
  :soc :soc,
  :institution :unitid,
  :earnings nil},
 :program-count 0,
 :note
 "No program->field->occupation chain found — see graph-stats cross-source-links for where the connection broke."}
```

## Earnings→program verdict (the MEASURED OUTCOME)

Did the builder discover the bridge between earnings (one source's institution-id encoding) and programs (another's) ON ITS OWN? Measured from the read-back graph + the builder's own reasoning trace. The driver named no key and added no driver-side join.

```clojure
{:earnings-edge-count 0,
 :builder-key-reasoning
 [{:source :ipeds,
   :line
   "I am starting by listing the tables in the SQLite database to identify relevant classes (e.g., Institutions, Programs, CIP codes) for the Louisiana education ontology. I will also check for behavioral patterns in the classifier to see if there are optimized workflows for SQL-based ontology discovery. Following the instructions, I am calling the tools directly to explore the schema before designing the extraction."}
  {:source :ipeds,
   :line
   "I am exploring the specific tables in the IPEDS SQLite database that match the Louisiana education goal. I've identified `HD2022` (Institutional Characteristics), `IC2022` (Student Charges/Options), `C2022_A` (Completions/Programs by CIP), and `CIPCodes` as primary candidates. I will examine their schemas to find the standard linking keys (UNITID for institutions, CIPCODE for programs) and check for Louisiana-specific filters (e.g., FIPS state codes). This direct sampling approach follows the task instructions to avoid complex `emit-tree!` calls for simple SQL discovery and mapping."}
  {:source :ipeds,
   :line
   "I am extracting institutional and program data specifically for Louisiana as required by the domain goal. I have identified `HD2022` for institutions (filtering by FIPS code 22 for Louisiana), `CIPCodes` for the field-of-study taxonomy, and `C2022_A` for program completions linking institutions to CIP codes. I will use stable URI schemes like `unitid:<id>` and `cip:<code>` to ensure connectivity with other sources. Alternative approaches like processing every institution were rejected to maintain focus on the Louisiana subset."}
  {:source :ipeds,
   :line
   "Filtered HD2022 for Louisiana (FIPS 22); extracted institutions, CIP taxonomy, and offersProgram relations based on completions in C2022_A."}
  {:source :wages,
   :line
   "The exploration in Iteration 1 successfully identified the CSV structure and provided a representative sample of rows. The source contains Standard Occupational Classification (SOC) codes (`soc_code`), occupation titles (`soc_title`), and various labor market outcomes including education requirements, star ratings, and multiple wage percentiles. \n\nI am choosing a direct transform approach as instructed for sampling tasks, rather than a behavior tree, to stay within budget and minimize overhead. I adopted the `soc:` URI scheme to ensure cross-source linking with other occupation data providers. I rejected creating separate concepts for \"Education Required\" categories, instead treating them as labels/attributes of the occupations to keep the ontology focused on entities (Occupations). I am converting relevant numeric fields (wages, growth percentages, star ratings) to native numbers in the `:attributes` map to ensure they are queryable. I am processing a larger sample (up to 100 rows) to ensure wide coverage of the Louisiana occupation set as requested."}
  {:source :pseo,
   :line
   "I am starting by exploring the structure of the Excel workbook `pseo_la.xlsx`. My first objective is to list the available sheets and then inspect the column headers of the most relevant-looking sheets to identify CIP codes, institution IDs (UNITID), and earnings/outcome data. I am also calling `classify-behaviors` to align with established patterns for ontology discovery from structured sources. I will use the excel tools directly as instructed, avoiding `emit-tree!` for these preliminary sampling steps."}
  {:source :pseo,
   :line
   "The previous attempt failed because I passed a map representing a sheet instead of the sheet's name or index to `sheet-columns` and `sample-rows`. I will correct this by using the literal sheet names \"Earnings\" and \"Flows\" discovered in the error message. I'm also choosing to call `classify-behaviors` to align with the prompt's requirement, though I will execute the exploration directly as requested. I'll inspect the \"Earnings\" and \"Flows\" sheets to identify UNITID (institution), CIP (program), and earnings outcomes as required by the domain goal."}
  {:source :pseo,
   :line
   "The exploration of `pseo_la.xlsx` reveals two main sheets: `Earnings` and `Flows`. The `Earnings` sheet provides a rich set of data linking institutions (`institution`), degree levels (`Degree Award Level`), and programs (`cipcode`) to employment outcomes ($1, 5, 10$ year earnings percentiles). The `Flows` sheet provides similar mappings but focuses on employment counts and geographic/industry flows. I will use the identified keys to mint canonical URIs: `unitid:<institution>` for institutions (PSEO uses 6-digit OPEIDs or UNITIDs, here likely the 6-digit ID), `cip:<cipcode>` for programs, and `degree:<level>` or `outcome:<type>` for related concepts. To ensure connectivity with other sources, I will treat the `cipcode` and `institution` codes as stable identifiers. I will process a significant sample of rows from the `Earnings` sheet to capture the variety of programs and institutions across Louisiana, including the associated earnings attributes. I am skipping an `emit-tree!` call in favor of direct processing as the task instructions state it is too slow for simple sampling tasks and I have sufficient data from the preliminary exploration."}
  {:source :pseo,
   :line
   "In the previous iteration, I incorrectly passed a 4th argument (options map) to `sample-rows`. According to the tool definition, the limit and offset should be passed within a single map as the 3rd argument if not using a simple integer. I am sticking to the `Earnings` sheet as it contains the crucial mapping between institutions (`unitid:`), programs (`cip:`), and numeric earnings outcomes required by the domain goal. I will process a representative sample, ensuring I filter out non-data header rows and aggregate rows (where cipcode is \"00\"). I am skipping `emit-tree!` as direct processing of the sampled data is more efficient here."}
  {:source :pseo,
   :line
   "Minted canonical unitid: and cip: URIs for Louisiana institution-program pairs"}],
 :institution-id-overlap-count 0,
 :earnings-connects-to-program-side? false,
 :earnings-edges-by-link {},
 :earnings-concept-count 0,
 :bridge-discovered? false,
 :sample-institution-overlap [],
 :connects-to-kinds #{}}
```

## Retrievability — labeled hybrid-search hits

```clojure
{"psychology bachelor's degree"
 [{:uri "unitid:447962",
   :label "Compass Career College",
   :score 0.01639344262295082}
  {:uri "soc:25-1194",
   :label "Career/Technical Education Teachers, Postsecondary",
   :score 0.016129032258064516}
  {:uri "unitid:373456",
   :label "Blalock's Professional Beauty College",
   :score 0.015873015873015872}
  {:uri "unitid:160621",
   :label "Southern University and A & M College",
   :score 0.015625}
  {:uri "soc:11-9033.00",
   :label "Education Administrators, Postsecondary",
   :score 0.015384615384615385}],
 "social work program"
 [{:uri "soc:11-9151.00",
   :label "Social and Community Service Managers",
   :score 0.01639344262295082}
  {:uri "soc:11-9151",
   :label "Social and Community Service Managers",
   :score 0.016129032258064516}
  {:uri "soc:43-1011",
   :label
   "First-Line Supervisors of Office and Administrative Support Workers",
   :score 0.015873015873015872}
  {:uri "soc:15-1231",
   :label "Computer Network Support Specialists",
   :score 0.015625}
  {:uri "soc:11-2032.00",
   :label "Public Relations Managers",
   :score 0.015384615384615385}],
 "registered nurse occupation"
 [{:uri "soc:11-9111",
   :label "Medical and Health Services Managers",
   :score 0.01639344262295082}
  {:uri "soc:13-1151",
   :label "Training and Development Specialists",
   :score 0.016129032258064516}
  {:uri "soc:11-9171",
   :label "Funeral Home Managers",
   :score 0.015873015873015872}
  {:uri "unitid:158325",
   :label
   "Baton Rouge General Medical Center School of Nursing & School of Radiologic Technology",
   :score 0.015625}
  {:uri "soc:15-2011",
   :label "Actuaries",
   :score 0.015384615384615385}],
 "computer science engineering"
 [{:uri "soc:11-3021.00",
   :label "Computer and Information Systems Managers",
   :score 0.01639344262295082}
  {:uri "soc:13-1081.01",
   :label "Logistics Engineers",
   :score 0.016129032258064516}
  {:uri "cip:01.1103",
   :label "Horticultural Science.",
   :score 0.015873015873015872}
  {:uri "soc:11-9041.00",
   :label "Architectural and Engineering Managers",
   :score 0.015625}
  {:uri "cip:01.1003",
   :label "Brewing Science.",
   :score 0.015384615384615385}],
 "clinical psychologist earnings"
 [{:uri "soc:13-1141.00",
   :label "Compensation, Benefits, and Job Analysis Specialists",
   :score 0.01639344262295082}
  {:uri "soc:11-3111.00",
   :label "Compensation and Benefits Managers",
   :score 0.016129032258064516}
  {:uri "soc:13-2052",
   :label "Personal Financial Advisors",
   :score 0.015873015873015872}
  {:uri "soc:13-2051",
   :label "Financial and Investment Analysts",
   :score 0.015625}
  {:uri "soc:11-3111",
   :label "Compensation and Benefits Managers",
   :score 0.015384615384615385}]}
```
