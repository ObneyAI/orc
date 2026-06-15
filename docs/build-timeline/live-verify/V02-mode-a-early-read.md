# V02 — Mode A early read (brownfield BRYC graph) — LIVE VERIFY

**Status:** done, awaiting HITL sign-off. **Date:** 2026-06-15.
**Branch:** `feature/ontology-architecture`. **Slice:**
`docs/build-timeline/issues/ontology-verification/V02-mode-a-early-read.md`
(PRD milestone M1). **Driver:** `development/src/v02_mode_a.clj`.
**Model:** `google/gemini-3-flash-preview` (real OpenRouter call).
**Embeddings:** real local `all-MiniLM-L6-v2` (DJL, 384-dim). **No mocks.**

This is the brownfield early read: ingest the EXISTING production BRYC graph
(`louisiana_programs_full.ttl`) into the NEW substrate, auto-embed/index it, and
run a per-vertical RLM exploration read for the Trinity profile — the early
signal on substrate + retrieval + exploration quality with ZERO new ingestion
work, and the "bring your own graph, we improve + extend it" proof.

The adversarial reference is the recorded old output:
`daryls-area51/docs/BRYC-GRAPH-ANALYSIS.md` (Trinity/Aminata/Reagan).

---

## TL;DR for the reviewer

1. **The shipped S09 TTL ingest does NOT recognize the production BRYC graph.**
   The 45 MB TTL parses fine (119,348 triples, ~6 s) but ingests **0 concepts /
   0 relationships** — only the `owl:Ontology` header lands. Root cause: S09
   classifies a subject as a concept ONLY when it is typed `skos:Concept`, and
   the production TTL has ZERO `skos:Concept` subjects — it is typed with DOMAIN
   classes (`edu:EducationalProgram` ×1599, `cip:CIPCode` ×447,
   `onet:Occupation` ×434) under `example.org` namespaces that aren't in S09's
   prefix table. **This is a real ingest-path gap — reported, not fixed here**
   (V06 owns that path; see "Bug found" below). It directly blocks the
   acceptance criterion "round-trip-faithful, not silently lossy" on a real
   brownfield graph.
2. **To still produce the early read on the REAL data**, the driver maps the
   TTL's domain individuals into the substrate via the PUBLIC create-concept /
   create-relationship commands (a driver-side adapter — NOT a change to the
   ingest path). This yielded **2,509 concepts + 4,642 relationships**.
3. **Auto-embed + retrieval work well.** All 2,509 concepts embedded (real local
   embeddings, ~18–22 s) and are retrievable via hybrid-search with
   semantically-correct hits. **ColBERT indexing TIMED OUT** at the 2,509-doc
   scale (60 s bridge timeout) — a real scale boundary; retrieval ran on the
   embedding + graph-BFS signals.
4. **The general RLM exploration is high quality** when given enough budget.
   4 of 5 verticals (career, financial, academic, preference) converged to
   grounded, profile-aligned recommendations with correct multi-hop
   program→CIP→occupation crosswalk navigation and honored HBCU preference.
   The **outcome** vertical could not converge — root cause: my adapter dropped
   the earnings attributes, so the model had nothing to ground on (mirrors the
   OLD system's own documented "earnings extraction from TTL" weakness).
5. **Budget finding:** an 8-iteration budget was too tight — all 5 verticals
   exhausted at 8 with 0 `final!` calls. At 16 iterations, 4/5 converged. The
   exploration was always *working* (no tool errors across 40+ iterations); it
   simply needed room to commit.

---

## 1. TTL ingest result — scale + round-trip (shipped S09 path)

Ran the SHIPPED `ttl-ingest/ingest-ttl!` over the full production TTL.

| Metric | Value |
|---|---|
| TTL file size | 47,148,625 bytes (≈45 MB) |
| Triples parsed (rdflib) | **119,348** |
| Blank nodes | 0 (flat individuals; inline embedding-vector literals dominate file size) |
| rdflib parse + URDNA2015 canonicalize | ~2.7 s (parse 1.9 s + canon 0.8 s) |
| Full `ingest-ttl!` (incl. canonicalize) | **~6.0 s** |
| Peak python RSS (canonicalize) | ~514 MB |

**Scale: FEASIBLE.** The 45 MB is mostly inline embedding strings on the
program nodes; the triple count (119 K) and absence of blank nodes keep
canonicalization fast and memory modest. No scale problem with the TTL itself.

**Round-trip faithfulness: FAILS on this graph.** Verbatim ingest report:

```clojure
{:ingest-ms 6014,
 :ingested? true,
 :triples-parsed 119348,
 :counts {:concept 0, :ontology-metadata 1, :relationship 0,
          :equivalence 0, :disjointness 0, :characteristic 0,
          :sub-property 0, :chain-axiom 0},
 :anomaly nil,
 :projected-concepts 0,
 :projected-relationships 0}
```

`:ingested? true` with `:anomaly nil` is a **silent** zero-ingest — the worst
shape for "not silently lossy". A consumer who only checks `:ingested?` would
believe the graph loaded. Source type distribution (from the raw TTL):

```
1599  a edu:EducationalProgram
 447  a cip:CIPCode
 434  a onet:Occupation
  97  a owl:DatatypeProperty
  58  a owl:ObjectProperty
  11  a edu:Awardlevel
  10  a edu:Discipline
   8  a edu:Sector
   7  a owl:Class
   1  a owl:Ontology      <-- the ONLY subject S09 ingested
   0  a skos:Concept      <-- S09 ingests concepts ONLY from this type
```

---

## 2. Auto-embed / index + retrievability (over the adapted graph)

Driver-side adapter mapped the domain individuals into the substrate:

```clojure
{:concepts 2509, :relationships 4642,
 :concept-kinds {:program 1599, :cip 447, :occupation 434,
                 :awardlevel 11, :discipline 10, :sector 8}}
```

Edges: `edu:hasCIPCodeEntity` (program→CIP), `cip:leadsToOccupation` (CIP→SOC),
`edu:hasSector`, `edu:hasAwardLevel` — the cross-source connective tissue.

Auto-embed + index (V01-style; real local embeddings):

```clojure
{:concepts 2509, :embedded 2509, :embed-ms 17851, :index-ms 65054,
 :index {:error "Bridge call timed out after 60000ms for method :create_index"}}
```

- **Embedding: 2,509 / 2,509 embedded** (real all-MiniLM-L6-v2), ~18 s.
- **ColBERT index: TIMED OUT** at 60 s for 2,509 docs. **Real scale finding** —
  the ColBERT bridge `:create_index` does not complete within the default 60 s
  timeout at this corpus size. Retrieval therefore ran on embedding + graph-BFS
  signals (the third signal absent). For M3's full head-to-head this must be
  budgeted/chunked or the timeout raised.
- **Also found:** the default in-memory **LMDB cache map-size is 10 MB**, which
  `MapFull`-crashes on a real-sized graph (2.5 K concepts × 384-dim
  embeddings). The driver bumps it to 4 GB. Relevant to any real-scale build
  using the LMDB cache default.

**Retrievability — confirmed.** Verbatim hybrid-search top-hits (embedding +
graph signals; labels come back `nil` — see gaps):

```clojure
{"social work program"
 [{:uri "cip:cip_44_0701" ...}   ; <-- the Social Work CIP, correct
  {:uri "soc:soc_21_1029" ...} {:uri "soc:soc_21_1022" ...} ...],
 "computer science engineering"
 [{:uri "cip:cip_11_0701" ...} {:uri "cip:cip_14_0903" ...}  ; CS + engineering CIPs
  {:uri "cip:cip_14_0901" ...} {:uri "cip:cip_11_0101" ...} ...],
 "psychology bachelor's degree"
 [{:uri "soc:soc_25_1066" ...}   ; psychology-teaching/clinical occupations
  {:uri "soc:soc_19_3033" ...} {:uri "soc:soc_19_3039" ...} ...],
 "early childhood education apprenticeship"
 [{:uri "cip:cip_13_1210" ...}   ; the Early Childhood Education CIP, correct
  {:uri "edu:10080_Morrison_Rd_1471" ...} ...]}
```

Semantically on-target across all four probes.

---

## 3. Per-vertical exploration read — Trinity (VERBATIM)

Real recursive-RLM exploration over the S19 graph tools (`graph-search`,
`neighborhood`, `get-concept`, `find-edges`, `filter-by-label-pattern`,
`exists?`, `absent-in-graph?`), 16-iteration budget, real LLM.

**Trinity profile:** GPA 3.0, ACT 24, super-purple (4-year focus), TOPS
opportunity (100% tuition), career fields Law / Psychology / Social Work,
HBCU preference (Southern, Xavier, Spelman, Howard) + LSU / Tulane.

### CAREER — `:final`, 14 iters, 6 recommendations

```clojure
{:recommendations
 ["edu:6400_Press_Drive_768" "edu:PO_Box_9374_Harding_Blvd_773"
  "edu:156_Thomas_Boyd_Hall_886" "edu:1_Drexel_Drive_1194"
  "edu:PO_Box_9374_Harding_Blvd_933" "edu:156_Thomas_Boyd_Hall_1120"],
 :connections
 [{:path ["edu:6400_Press_Drive_768" "cip:cip_44_0701"] :relation "edu:hasCIPCodeEntity"}
  {:path ["edu:PO_Box_9374_Harding_Blvd_773" "cip:cip_44_0701"] :relation "edu:hasCIPCodeEntity"}
  {:path ["edu:156_Thomas_Boyd_Hall_886" "cip:cip_42_0101"] :relation "edu:hasCIPCodeEntity"}
  {:path ["edu:1_Drexel_Drive_1194" "cip:cip_42_0101"] :relation "edu:hasCIPCodeEntity"}
  {:path ["edu:PO_Box_9374_Harding_Blvd_933" "cip:cip_22_0101"] :relation "edu:hasCIPCodeEntity"}
  {:path ["edu:156_Thomas_Boyd_Hall_1120" "cip:cip_22_0101"] :relation "edu:hasCIPCodeEntity"}],
 :reasoning
 "Trinity's profile favors HBCUs (Southern, Xavier) and major Louisiana institutions (LSU). I identified key CIP codes for her fields: Social Work (44.0701), Psychology (42.0101), and Law (22.0101). I then mapped these to specific programs at Southern University (SUNO/SUBR), LSU, and Xavier. These recommendations align with her 4-year focus and her career interests while remaining within Louisiana to utilize her TOPS Opportunity award."}
```

> A second run of this same vertical produced the full 3-hop paths
> program→CIP→occupation, e.g. `["edu:PO_Box_9374_Harding_Blvd_933" "cip:cip_22_0101" "soc:soc_23_1011"]`
> = "Law Program at Southern University leads to Lawyer occupation", plus the
> Psychology→Clinical-Psychologist and Social-Work→Social-Worker chains. The
> CIP→SOC crosswalk navigation is genuinely there.

### FINANCIAL — `:final`, 7 iters, 3 recommendations

```clojure
{:recommendations
 ["edu:PO_Box_9374_Harding_Blvd_780" "edu:156_Thomas_Boyd_Hall_886"
  "edu:PO_Box_9374_Harding_Blvd_773"],
 :connections ["cip:cip_42_0101" "cip:cip_44_0701" "edu:Public_4_year_or_above"],
 :reasoning
 "To maximize Trinity's TOPS Opportunity award (100% tuition coverage for public institutions), Southern University and LSU are the primary recommendations. Southern University's Psychology (Bachelor's) and Social Work (Bachelor's) programs, along with LSU's Psychology (Bachelor's) program, are all located at public 4-year institutions in Baton Rouge, ensuring full tuition is covered while aligning with her career interests and HBCU preference."}
```

### OUTCOME — `:exhausted-iterations`, 0 recommendations

`:final-output nil`. **Root cause (diagnosed, not hand-waved):** the model
spent all 16 iterations searching for `earnings-y1` / `earnings-y5` values
(verbatim iter-14 code: `(graph-search "earnings-y1 and earnings-y5 data for
Psychology and Social Work programs ..." ...)`) but the program concepts carry
NO earnings — confirmed by reading a `get-concept` result:
`{:description "program — Southern University at New Orleans, New Orleans
(Bachelor's)", :indicators ["42.0101" "Bachelor's degree" "New Orleans"] ...}`
(no earnings field). The earnings literals exist in the source TTL
(`edu:earningsY1Estimated`, `edu:earningsY5Estimated`) but my driver-side
adapter did not persist them onto the concept (create-concept stores only
label / description / indicators). So this is an **adapter-coverage limitation**
producing an honest "can't ground it" failure — NOT a substrate or RLM defect.
Notably this mirrors the OLD system's own recorded weakness: "⚠️ Earnings data
extraction from TTL" (BRYC-GRAPH-ANALYSIS.md, Areas requiring work).

### ACADEMIC — `:final`, 9 iters, 5 recommendations

```clojure
{:recommendations
 ["edu:6400_Press_Drive_850" "edu:PO_Box_9374_Harding_Blvd_780"
  "edu:6400_Press_Drive_71" "edu:8100_Hwy_71_South_825"
  "edu:6823_St_Charles_Avenue_1382"],
 :connections ["edu:hasAwardLevel" "edu:hasCIPCodeEntity" "cip:leadsToOccupation"],
 :reasoning
 "Trinity is 'super-purple' (4-year focus), making Bachelor's degrees the primary recommendation. Southern University (HBCU preference) offers Psychology at both the New Orleans and Baton Rouge campuses. LSU Alexandria provides a Bachelor's in Psychology, and Tulane offers a Bachelor's in Sociology (strong fit for Social Work/Law pathways). All listed programs match Trinity's career interests and are degree-granting 4-year programs."}
```

### PREFERENCE — `:final`, 10 iters, 4 recommendations

```clojure
{:recommendations
 ["edu:6400_Press_Drive_850" "edu:6400_Press_Drive_71"
  "edu:PO_Box_9374_Harding_Blvd_52" "edu:1_Drexel_Drive_895"],
 :connections
 ["edu:6400_Press_Drive_850 -> cip:cip_42_0101 (Psychology)"
  "edu:6400_Press_Drive_71 -> cip:cip_51_1501 (Substance Abuse/Social Work overlap)"
  "edu:PO_Box_9374_Harding_Blvd_52 -> cip:cip_51_2310 (Therapeutic professions)"
  "edu:1_Drexel_Drive_895 -> cip:cip_42_2801 (Clinical Psychology)"],
 :reasoning
 "Trinity's profile aligns with HBCU icons Southern University (SUNO and SUBR) and Xavier. At SUNO (6400 Press Drive), she has direct access to Psychology and Behavioral health tracks. SUBR (Harding Blvd) offers human service orientations, and Xavier (Drexel Drive) hosts professional psychology programs. These match her super-purple 4-year focus and career interests in Psychology and Social Work, while honoring her HBCU preference and named institutions."}
```

---

## 4. Adversarial comparison vs the recorded OLD outputs

Reference: `BRYC-GRAPH-ANALYSIS.md` Trinity section (5 bespoke explorers + RRF
fusion, hand-tuned, 14,018-node graph). This is a deliberately HIGH bar:
general new exploration vs purpose-built explorers.

| Dimension | OLD (bespoke explorers) | NEW (general RLM, this run) | Verdict |
|---|---|---|---|
| Career SOC↔CIP↔program navigation | Career explorer = the ONLY differentiating explorer; finds Law/Psych/SW programs | Multi-hop program→CIP→SOC paths surfaced explicitly with the relation each hop; correct CIP codes (22.0101 Law, 42.0101 Psych, 44.0701 SW) | **NEW ≥ OLD** — equal/better; the new side EXPOSES the path, old side reported counts |
| Per-vertical differentiation | Financial/Outcome 100% overlap across students (NOT personalized); Academic 95–100% overlap | Each vertical reasons from Trinity's actual profile (TOPS-public, super-purple-bachelor's, HBCU) and returns DIFFERENT, vertical-appropriate sets | **NEW > OLD** on the old system's WEAKEST point (its own report calls this the "Personalization Gap") |
| HBCU preference | "18 HBCU programs found (Southern, Xavier, Grambling)"; preference explorer "partially working" (81–100% overlap) | Honored across career/academic/preference — Southern (SUNO+SUBR), Xavier named explicitly | **NEW ≈ OLD** |
| Financial / TOPS grounding | TOPS coverage calc → fit score 0.95; concrete | Reasons TOPS-opportunity → public-4-year institutions; correct logic, but grounded on `award-category`/sector, not a numeric net-cost | **OLD ≥ NEW** — old has explicit cost math; new has correct directional logic only |
| **Earnings / outcome** | "Outcome explorer" runs; earnings Y1/Y5 vs $52,547 median (though old report flags earnings extraction as weak) | **FAILED — could not ground earnings** (adapter dropped the fields) | **OLD > NEW (WORSE)** — the one place the new read is strictly worse this run |
| Ranked scores | RRF scores per program (e.g. 0.0543), explicit top-5 | No numeric ranking; ordered list + per-item rationale | **OLD > NEW** on score legibility; **NEW > OLD** on per-item reasoning transparency |
| Output substance | Counts + Jaccard + score tables; little per-program "why" | Explicit reasoning + connection path per recommendation | **NEW > OLD** on explainability |
| Label legibility in tool output | (n/a — old explorers internal) | hybrid-search results return `:label nil`; the model works off URIs + get-concept | minor NEW gap (see §5) |

**Where NEW is clearly WORSE / missing (no hiding):**
1. **Outcome/earnings — strictly worse this run.** New produced nothing; old at
   least attempted earnings scoring. (Caveat: this is an adapter-coverage gap,
   not a substrate limit — earnings ARE in the substrate-able data; the adapter
   must carry them. M3's real builder must extract earnings as concept
   attributes or the outcome vertical will keep failing.)
2. **No numeric ranking / score** — the old RRF score gives a sortable
   confidence; the new read gives an ordered list without a comparable score.
3. **No net-cost math** in the financial vertical — directional logic only.
4. **hybrid-search returns `:label nil`** in results — the labels live on the
   concept but don't ride back on the result map; the model compensates with
   `get-concept`, costing iterations.

**Where NEW is clearly BETTER:**
1. **Personalization** — the old system's headline failure (4 of 5 explorers
   were non-differentiating; "similar top-10s for all students"). The new
   general exploration reasons per-vertical from the actual profile and returns
   distinct, appropriate sets.
2. **Explainability** — every recommendation carries a path + a one-line why;
   the old report is mostly counts/Jaccard/score tables.
3. **No bespoke explorer code** — the new side used ONE general RLM + the S19
   graph tools, with no per-vertical hand-tuned explorer, RRF weights, or
   color-profile filters. That it matches/beats hand-tuned explorers on most
   dimensions is the headline.

---

## 5. Live-verify capture details

- **Real LLM:** `google/gemini-3-flash-preview` via OpenRouter (key from
  `OPENROUTER_API_KEY` env var only — never committed). Key validated (no 401;
  all verticals returned real completions).
- **Real embeddings:** local DJL `all-MiniLM-L6-v2`, 384-dim, 2,509 concepts.
- **Real Grain substrate:** in-memory event-store-v3 + LMDB cache; commands →
  schema-validated events → projections; engine todo-processors started
  (s18-pattern) so Phase-2 ticks drive.
- **No mocks, no synthetic stand-in, no false green.** Tool errors across the
  40+ iterations of the first (8-iter) run: **zero**. All graph traversals
  returned real projection data.
- Full result EDN captured at `/tmp/v02_result16.edn` during the run (transient;
  the verbatim final outputs + the diagnostic transcripts are reproduced above).
- **Reproduce:** `OPENROUTER_API_KEY=… clj -M:dev` then
  `(require '[v02-mode-a :as v]) (def r (v/run! {:model "google/gemini-3-flash-preview" :max-iterations 16})) (v/print-verticals! r)`.

---

## 6. Bug found in the ingest path (REPORTED, not fixed — V06 owns it)

**`ingest-ttl!` silently zero-ingests a brownfield graph typed with domain
classes.** `components/ontology/src/ai/obney/orc/ontology/core/ttl_ingest.clj`
classifies a subject as a concept only when its `rdf:type` set contains
`skos:Concept` (the `concept-subjects` filter in `ingest-ttl!`). The production
BRYC TTL has 0 `skos:Concept` subjects — its individuals are typed
`edu:EducationalProgram` / `cip:CIPCode` / `onet:Occupation` under `example.org`
namespaces absent from S09's `known-prefixes` table. Result: 0 concepts, hence
0 relationships (relationships are only emitted from concept subjects), with
`:ingested? true` and `:anomaly nil` — a **silent** loss.

Two distinct sub-issues for whoever fixes the ingest path:
- **(a) Concept-type recognition** is hardcoded to `skos:Concept`. A brownfield
  ingest must either accept a caller-supplied concept-type set (e.g.
  `#{edu:EducationalProgram cip:CIPCode onet:Occupation}`) or treat
  `owl:NamedIndividual` / any `owl:Class`-typed subject as a concept.
- **(b) Silent-loss shape** — an ingest that recognizes 0 of 119,348 triples'
  subjects should NOT return `:ingested? true :anomaly nil`. It should surface a
  "recognized 0 concepts of N typed subjects" warning so a consumer can't get a
  false green (this directly violates the slice's "not silently lossy" AC).

I did NOT touch `ttl_ingest.clj` / `deterministic_skeleton.clj` /
`rlm_discovery.clj` / the source-tool nses / `rlm_sandbox.clj` /
`sandbox_tools.clj` (V06 + others own those). Route the fix accordingly.

---

## 7. Concerns for M3 (the full head-to-head)

1. **Concept-type recognition is the gating ingest fix.** Until (6a) lands, the
   new builder can't ingest the production TTL (graph A2) at all, and any
   brownfield "bring your own graph" claim is blocked. M3's A2 leg depends on it.
2. **Earnings (and other numeric outcome attrs) MUST become concept
   attributes**, or the outcome vertical fails on BOTH sides' weak spot and the
   new side looks strictly worse. Whatever builds graph B must carry
   `earningsY1/Y5`, tuition, net-cost as queryable concept data — not just
   label/description/indicators.
3. **ColBERT at scale.** The 60 s bridge timeout is hit at ~2.5 K docs; the full
   5-source graph will be larger. Budget a longer timeout or chunked indexing,
   or the ColBERT signal silently drops out of hybrid-search (RRF still runs on
   2 signals, so it won't error — it'll just quietly under-retrieve).
4. **LMDB map-size default (10 MB)** MapFulls on a real graph. The harness must
   set an adequate map-size (the driver uses 4 GB).
5. **hybrid-search `:label nil`** in results costs the explorer iterations
   (forces a get-concept round-trip per hit). Carrying labels on result maps
   would tighten exploration and reduce token/iteration cost in the comparison.
6. **Iteration budget is a fairness control.** 8 was too tight (0/5 converged);
   16 gave 4/5. M3 must fix the budget identically for old re-run and new, and
   report convergence/non-convergence as a first-class outcome (a vertical that
   never commits is a real result, not a discard).
7. **Numeric ranking / score.** If the comparison rubric weights "ranked,
   scored recommendations", the new general RLM currently returns ordered lists
   without a comparable score — decide whether that's a required dimension and,
   if so, prompt the new side to emit a score, else it loses a dimension on
   presentation rather than substance.
8. **This is the brownfield BASELINE read, on a driver-side adapter** — NOT the
   real new builder. M3's graph B must come from the real source-ingestion path
   (V06), so re-validate these exploration quality signals against B, not this
   adapter.
