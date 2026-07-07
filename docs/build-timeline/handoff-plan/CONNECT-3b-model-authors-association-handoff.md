# Handoff — CONNECT-3b: model AUTHORS the association-spec (prototype → tdd → live)

**Issue:** `docs/build-timeline/issues/onet-connectivity/CONNECT-3b-model-authors-association.md` (read it — the full design + acceptance). CONNECT-3a landed the deterministic associative FOLD (`9d24d8d9`); this makes the MODEL discover when to author it + lands the rating on the edge. **PROTOTYPE FIRST** (test-the-builder): prove the model DISCOVERS associative modeling before hardening.
**Branch:** `feature/ontology-architecture` · commit-LOCAL only, NEVER push. OPENROUTER_API_KEY = shell env var only. `pgrep -f` hygiene; detached `nohup … &` for live runs (Bash-tool background reaped this session).

## Read first
- `container_aggregate.clj:504` `aggregation-author-guidance` — the model's spec-authoring prompt (cases A tall→attribute, B one-row→transform). You ADD case C (shared-entity element → association-spec).
- `container_aggregate.clj:454` `parse-aggregation-spec` — accept/pass `:predicate` + `:element-entity-type`.
- `container_aggregate.clj` `associate-finalize` (CONNECT-3a) — where the edge is built; thread the `:value-col` value into the relationship-draft's keyword-keyed `:properties` (NOT `:attributes` — that's dropped by the compile path; CONNECT-3a proved this).
- `rlm_discovery.clj` `relationship-draft->command` — confirm it forwards `:properties` (keyword-keyed) so the rating lands.
- `extract_subbehavior.clj:724-749` — the AUTHOR node (`:instruction` includes `aggregation-author-guidance`; model `:writes :aggregation-spec`). This is the live seam the prototype exercises.

## STEP 1 — PROTOTYPE (do this FIRST, report before hardening)
Draft the case-C guidance text, then in a throwaway `development/src/connect3b_authoring_prototype.clj`: feed the model a REAL O*NET Skills sample (a few sampled rows of SOC + Element Name + Data Value + Scale ID) + the model-spec + the new guidance (the same author prompt shape the pipeline uses), and read back the authored `:aggregation-spec`. **Does it carry `:predicate` + `:element-entity-type` (association) — i.e. did the model DISCOVER that skills are shared entities → edges?** Run 5–8× (LLM variance); report the HIT-RATE + a sample of the authored spec + any run that chose attribute instead. Also sanity-check an Alternate-Titles sample still → attribute (entity-specific labels, not shared). If the model can't reliably discover it, iterate the guidance wording (structural signal: "the SAME element value recurs across MANY different entity keys" + semantic "is itself a referenceable entity you'd traverse to") — do NOT hardcode "Skills"/"requires". **Report the prototype verdict; only proceed to STEP 2 if the model reliably (majority) authors the association.**

## STEP 2 — TDD (after the prototype proves discovery)
1. **Guidance case C present + A/B intact** (pure text/shape unit): the guidance string contains the shared-entity/association case; and a deterministic-fixture check that a shared-across-keys element sample would map to an association-spec while an entity-specific-label sample maps to a collect attribute (test the DECISION shape via the parse + a small fixture, not the LLM).
2. **parse-aggregation-spec** round-trips `:predicate` + `:element-entity-type` (incl. the C1 string/normalized forms).
3. **Rating on the edge:** associate-finalize puts the `:value-col` value in the relationship-draft `:properties` (keyword-keyed) → after landing, the occupation→element edge's `:properties` carries the rating (assert through the real create-relationship command, like CONNECT-3a's landing test).
4. Collect/top-N attribute modes byte-identical; existing container_aggregate tests green.

## STEP 3 — LIVE VERIFY (durable + live QA)
Bounded O*NET build (`{:only [:onet] :max-containers 10 :max-windows 5 :store :sqlite}`, detached, -Xmx6g). Then forensically (reuse `development/src/onet_overmint_forensics.clj` edge-scan — update its db/oid/tenant to the run) confirm: occupations participate in cross-sheet EDGES to shared skill/task nodes (occupation edge-participation > 0, was 0), and BFS occupation↔skill↔occupation traverses. The connection is the MODEL's discovery (report which junction sheets it modeled associatively). Rule out the harness: verify the ontology-id from the tags (the forensics caught wrong-oid twice).

## Do NOT
Hardcode the association decision, predicate, or entity-type (test-the-builder). Touch CONNECT-1/2/3a fold internals beyond the rating-`:properties` thread, MC-6, the excel relations. No domain names in guidance/code.

## Gate + hygiene
`clj -M:poly test brick:ontology` green (detached) + the prototype hit-rate + the live edge-scan. ONE JVM at a time; 0 orphan this-repo JVMs; consolidator flake — isolate ×3 if sole red.

## Deliverable — final message: STEP 1 prototype hit-rate + sample authored spec (the model's OWN discovery) + the Alt-Titles→attribute sanity; STEP 2 tracers red→green (gate line, exit 0) + the guidance/parse/rating diffs + the rating-on-edge assertion; STEP 3 live edge-scan (occupation edge-participation before 0 → after N; which sheets modeled associatively; BFS traverses) + the ontology-id you verified; collect/top-N unregressed; anything not verified (esp. LLM variance); no commit/push; 0 orphan JVMs.

## Core Disciplines (verbatim)
1. NEVER assume — chase to ROOT CAUSE; rule out the harness (verify the ontology-id from tags). 2. Verify QUALITY: the live occupation edges + BFS traversal is the proof, not "the guidance mentions association". 3. Instrument the prototype hit-rate to real numbers. 4. Durable tests AND live QA — prototype + live build. 5. No silent fallback. 6. TDD, tests first. 7. No hardcoded domain matching. 8. Re-orchestrate — extend the existing guidance/fold, don't fork. 9. TEST THE BUILDER — the model must DISCOVER the associative modeling; never feed it "Skills→requires". 10. Deterministic where deterministic (parse, rating). 11. Key = env var; JVM hygiene (detached, one at a time, `pgrep -f`, 0 orphans). 12. Domain-agnostic. 13. Report the honest LLM hit-rate incl. misses.
