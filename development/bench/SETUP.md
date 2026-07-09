# Bench Setup — first-run flow

ColBERT (late-interaction retrieval) powers the optional `:colbert` signal in
the ontology's hybrid search — used by BOTH the agent-facing R-Inject
classifier AND the general-purpose evolutionary-ontology extraction pipeline
(Cambot path). It runs **entirely on the JVM** (ADR 0002:
`docs/adr/0002-pure-jvm-colbert-signal.md`): the `answerai-colbert-small-v1`
encoder checkpoint on DJL OnnxRuntime + HuggingFace tokenizers, scored with
exact MaxSim. There is no Python environment, no subprocess bridge, and no
setup script.

This document walks a fresh-machine setup from clean clone through a
successful `(bench/run! legal-issue-detection/task)`.

## 1. One-time machine setup

### Prerequisites

- Java 21+ and the Clojure CLI (`clj`) installed
- ~150 MB of free disk space for the encoder checkpoint, downloaded into a
  local cache on first use
- `OPENROUTER_API_KEY` in the environment (the benches make real LLM calls)

That's the whole list. The first ColBERT use in any JVM resolves the encoder
checkpoint:

1. `-Dcolbert.model.path=/abs/dir` — operator override; skips the network
   entirely (air-gapped machines).
2. `~/.cache/orc/colbert/answerai-colbert-small-v1/` — the local cache, used
   when it already holds every artifact at the expected byte size.
3. Otherwise the missing artifacts (~133 MB, dominated by `model.onnx`)
   download from the HuggingFace CDN into that cache, each verified by byte
   size. This happens ONCE per machine; every later run is offline.

### Sanity check — venv-less rerank

Prove the JVM signal end-to-end without bringing up the bench runner:

```bash
clj -M:dev -e '(require (quote [ai.obney.orc.colbert.interface :as colbert]))
               (println (colbert/rerank {} {:query "arbitration clause"
                                            :documents ["binding arbitration governs disputes"
                                                        "the weather is nice today"]
                                            :k 2}))'
```

Expected (~5-10s cold — the one-time encoder load; milliseconds warm): two
results, the arbitration document ranked first. On a fresh machine the first
invocation logs the model download before scoring.

## 2. Using ColBERT from a downstream app (orc as a git/SHA dependency)

Nothing extra. The model cache lives under the user home (not the repo), so a
read-only gitlibs checkout works as-is. Index artifacts are written under the
JVM working directory by default (`.orc-colbert-indexes/`); point
`-Dcolbert.index.root=/abs/path` somewhere writable if your app's working
directory is read-only, and `-Dcolbert.model.path` at a pre-downloaded model
directory if the machine cannot reach the HuggingFace CDN.

## 3. First-run flow

```clojure
clj -M:dev -e '(require (quote [runner :as r])) (r/start!)'
```

Expected output (annotated):

```
Emitting synthetic padding (80 entries for FAISS clustering floor)...
Seeding description corpus (45 hand-authored seeds)...
Driving concept-graph projectors...
Building ColBERT description index (one-time, expect seconds)...
Index state: {:events-since-last-rebuild 0, :last-rebuild-timestamp "...", :index-built? true}
Benchmark system ready.
```

What to look for:

- The 80 synthetic-padding entries are a holdover from the Python-era index
  builder, which needed a minimum corpus size for its clusterer. The pure-JVM
  index has no such floor, but the runner still emits them; they're tagged so
  they never surface in retrieval results.
- `:index-built? true` is the canonical "ColBERT cold-start succeeded"
  signal. Without it, retrieval (`classify-task` / `classify-behaviors`)
  returns 0 hits because there's nothing in the index yet.
- Index builds are fast on the JVM: the corpus encode is the cost (seconds
  for the seed corpus after the one-time encoder load). Subsequent
  `(start!)` calls are similar — the encoder loads once per JVM.

## 4. Running benches

### Single task

```clojure
(require '[runner :as r])
(require '[legal-issue-detection :as task])
(r/start!)
(r/run! task/task)
(r/stop!)
```

### Full suite

```clojure
(require '[all :as bench])
(bench/start!)
(bench/run-all!)
(bench/summary!)
(bench/stop!)
```

### Result location

Each task run saves an EDN to:

```
development/bench/generalization-results/<task-slug>_<YYYY-MM-DD>_<HHMMSS>.edn
```

The EDN carries:

- `:status` — `:success` if the run completed within timeout
- `:generated-tree-raw` — the model's emitted behavior tree (S-expression form)
- `:r-inject-trace` — the prepend the model received (when `:rlm
  {:auto-classify? true}` is set on the task)
- `:iteration-reasonings` — per-iteration `:reasoning` text from the model
- `:usage` — token counts (prompt / completion / total)
- `:outputs` — the final task outputs

### What's reproducible across runs

- The classifier's `:assigned-tree-id` is stable when classify-task scores
  cleanly above the floor (typical for the 5 bench tasks).
- Reranker confidences are stable to within ~5% across runs.
- Token totals vary ±10-15% per run because the model's generated tree
  shape and reasoning prose are non-deterministic at temperature 0.2.

## 5. Adding your own seeds

Hand-authored tree-class and behavioral-subtree seeds live in the shipped
component resources at `components/ontology/resources/seeds/*.edn` (the
canonical source). The dev shim at `development/src/seed_descriptions.clj`
loads from those EDN files and re-exports named-var access for in-tree
tests. Each seed is a static map with:

- A stable target-id (typically derived via `nameUUIDFromBytes` over a string
  like `"seed:tree:<name>"` so re-emitting doesn't create a new identity)
- A description body with `:capabilities` / `:strengths` / `:weaknesses` /
  `:representative-uses` / `:avoid-when` / `:summary`
- Either `:scope :tree-class` (Layer 1, structural) or
  `:scope :behavioral-subtree` (Layer 2, behavioral)

After adding a seed:

1. Edit the relevant `.edn` file directly under
   `components/ontology/resources/seeds/`.
2. Restart the runner (`(r/stop!)` then `(r/start!)`). The seed emit is part
   of `seed-corpus-and-build-index!` and runs at startup, reading from the
   updated EDN files.
3. Verify the new seed is in the index:
   ```clojure
   (require '[ai.obney.orc.ontology.interface :as ontology])
   (ontology/search-descriptions ctx {:query "<text matching your :summary>"
                                       :granularity :tree-class
                                       :k 3})
   ```
4. The new seed should appear in the top-K with a positive score.

## 6. Troubleshooting

| Symptom | Cause / fix |
|---|---|
| `ColBERT model artifact failed size verification` | A download was truncated (network hiccup). Delete the named file under `~/.cache/orc/colbert/answerai-colbert-small-v1/` and re-run — the resolver re-fetches and re-verifies. |
| `-Dcolbert.model.path points at ... missing required model artifacts` | The override directory is incomplete. It must contain `model.onnx`, `tokenizer.json`, and the config files (the error names exactly what's missing). |
| `:colbert-index-artifact-unreadable` | The index directory has no `orc-colbert-index` format marker — a foreign or legacy Python-era PLAID layout. Index artifacts are derived data: rebuild via `create-index!` (the reindex processor does this automatically for the description corpus); `hybrid-search` degrades to 2 signals in the meantime. |
| First run is slow / appears to hang for ~10s | One-time model download (fresh machine) and/or the one-time encoder load in this JVM. Subsequent calls are milliseconds. |

### `:r-inject-trace` is nil on a saved EDN even though task has `:rlm {:auto-classify? true}`

Three possible causes:

1. The runner wasn't started fresh — `(r/start!)` builds the corpus index;
   without it, classify-task has nothing to retrieve. Re-run `(r/start!)`
   and verify `:index-built? true`.
2. The reranker fell back to ColBERT (low-confidence top-1). The EDN's
   `:r-inject-trace.:classifier-payload.:structural.:rerank-fallback?` would
   be `true` in that case.
3. The task's structural fingerprint didn't match anything above the
   `min-display-confidence` floor (0.6). Try lowering the floor temporarily
   in `todo_processors.clj` to confirm.

## 7. Reproducing report numbers

The reports under `development/bench/r_inject_reports/` cite specific EDNs
from earlier runs. Clean re-runs **will not** produce byte-identical
EDNs (LLM non-determinism + timestamp fields), but they will produce
**structurally-comparable** ones:

- `:r-inject-trace.:prepend` — the corpus-suggestions block. Should start
  with `"## Suggested patterns from corpus"` and include the matched seed
  name with a confidence in the `[:context :tree-id]` neighborhood of the
  reports.
- `:r-inject-trace.:classifier-payload.:structural.:top-candidates[0]
  .:fitness-score` — for `legal-issue-detection`, this is ≈ 1.00 because
  the task description matches the seed almost verbatim.
- `:usage.:total-tokens` — in the 40K-60K range for `legal-issue-detection`.
  Higher = the model emitted a more complex tree; lower = it short-circuited
  to a single LLM call.
- `:iteration-reasonings[0]` — the first iteration's `:reasoning` should
  reference the corpus-prepended patterns (when classify-task fired) or
  state explicitly that no high-confidence match was found.

When in doubt, compare two recent EDNs from the same task to anchor the
expected variance band.

## Cross-references

- `docs/adr/0002-pure-jvm-colbert-signal.md` — why the signal is pure JVM
- `docs/COLBERT-INTEGRATION.md` — the JVM architecture (encoder checkpoint,
  exact MaxSim, index artifact, model resolution)
- `components/colbert/src/ai/obney/orc/colbert/interface.clj` — public API
- `components/ontology/resources/seeds/` — shipped baseline seed corpus
- `development/bench/r_inject_reports/` — expected-output reference EDNs
