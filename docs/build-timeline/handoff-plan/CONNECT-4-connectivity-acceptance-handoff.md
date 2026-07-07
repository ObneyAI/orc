# Handoff — CONNECT-4: durable connectivity acceptance (+ confirm CONNECT-3c live)

**Issue:** `docs/build-timeline/issues/onet-connectivity/CONNECT-4-connectivity-acceptance.md`. Closes the connectivity line: a DURABLE verdict (unit-tested pure fn) + a bounded LIVE O*NET build that proves graph B is connected AND that CONNECT-3c's edges attach to CANONICAL `occupation/<SOC>` nodes on a real build (unit proved the scheme; live proves the full-URI match).
**Branch:** `feature/ontology-architecture` · commit-LOCAL only, NEVER push. OPENROUTER_API_KEY = shell env only. `pgrep -f` hygiene; detached `nohup … &` (Bash-tool background reaped).

## Read first
- `development/src/onet_overmint_forensics.clj` — the edge-scan / reduce-concepts / reduce-relationships reads to reuse (update its db-file/oid/tenant to THIS run's — read them from the SQLite `event_tags`/`events`, NEVER trust the log-banner oid; wrong-oid was caught twice).
- `development/src/eb12_graph_b_central_evolver.clj` `run!` — the bounded driver (`{:only [:onet] :max-containers 10 :max-windows 5 :store :sqlite}`); `graph-stats`/`connectivity-proof` helpers.
- `development/src/mt7c_acceptance.clj` — the pure-verdict-fn + live-driver PATTERN to mirror (pure `*-verdict` fn is the durable /tdd deliverable; live is the QA).

## What to build
1. **Pure verdict fn** (durable, TDD) — e.g. `connectivity-verdict` taking a graph summary `{:occupation-edge-participation N :canonical-source-edges N :entity-stub-edges N :bfs-canonical->related? bool :element-node-counts {…}}` → `{:pass? bool :reasons […]}`. PASS iff: occupation edge-participation > 0 (a threshold, not 0); source-uris are canonical `occupation/*` (entity-stub-edges = 0 — the CONNECT-3c guarantee); BFS from a canonical occupation reaches a DIFFERENT canonical occupation through a shared element. TDD it with synthetic summaries (connected → pass; 0-edges → fail; all-entity-stub → fail; occupation-island → fail).
2. **Live bounded driver + forensic read** — run the bounded O*NET build DETACHED (-Xmx6g); wait for extraction+landing (embedding begins — edges are landed by then; do NOT wait for the slow CQ loop); read the store forensically:
   - occupation cross-sheet edge participation (expect > 0; was 0 pre-CONNECT-3).
   - source-uri SCHEME breakdown of the association edges — assert `occupation/*` (canonical), ZERO `entity/*` stubs (CONNECT-3c live confirmation — THE new thing this slice proves).
   - a sampled canonical `occupation/<SOC>` → its skills AND tasks/knowledge (across sheets).
   - BFS (real `build-concept-graph` + `expand-concept-neighborhood`) `occupation/<SOC>` → element → a DIFFERENT `occupation/<SOC>`.
   - Feed the summary into the pure verdict fn → PASS.

## Do NOT
Touch CONNECT-1/2/3 code (this is acceptance, not a fix), the caps, MC-6. Reuse the forensics + eb12 harness. Verify the ontology-id from the tags before any read (rule out the harness). No domain names in the verdict fn.

## Gate + hygiene
`clj -M:poly test brick:ontology` green (the verdict-fn unit tests) + the LIVE forensic read (the QA). ONE JVM at a time; kill the build JVM after reading; 0 orphan this-repo JVMs; `pgrep -f`. Detached builds; note the CQ-loop slowness (read edges after landing).

## Deliverable — final message: the pure verdict-fn + its red→green unit tests (gate line, exit 0); the LIVE forensic result — occupation edge-participation (before 0 → after N), the source-uri scheme breakdown proving `occupation/*` canonical + ZERO `entity/*` stubs (CONNECT-3c confirmed live), a sampled occupation's skills+tasks, the BFS canonical→related traversal, the element-node dedup counts; the ontology-id you verified from tags; the verdict PASS; anything not verified (esp. if the CQ loop had to be killed — say so); no commit/push; 0 orphan JVMs.

## Core Disciplines (verbatim)
1. NEVER assume — RULE OUT THE HARNESS: verify the ontology-id from the tags (a green acceptance on the wrong oid/store is a false green — caught twice already). 2. Verify QUALITY: the live edges on CANONICAL occupations + BFS traversal is the proof, not "edges exist somewhere". 3. Instrument the counts. 4. Durable tests AND live QA — the pure verdict fn is guarded on every run; the live build is the QA. 5. No silent fallback. 6. TDD the verdict fn, tests first. 7. No hardcoded domain matching. 8. Re-orchestrate — reuse forensics + eb12; don't fork. 9. Adversarial: confirm the edges are CANONICAL occupation/* (zero entity/* stubs) — the exact CONNECT-3c guarantee — and BFS reaches a DIFFERENT occupation. 10. Deterministic connection (MC-6/associative from source, not LLM-variance for the traversal). 11. Key = env var; JVM hygiene (detached, one at a time, `pgrep -f`, 0 orphans). 12. Domain-agnostic. 13. Report honestly if the CQ loop was killed to read edges.
