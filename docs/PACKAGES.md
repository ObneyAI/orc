# Packages

ORC is published as standalone packages from a single Polylith repo — the same
model [grain](https://github.com/ObneyAI/grain) uses. **Pull in only the package
you need** to keep your dependency footprint minimal.
Each package is a `projects/<name>` with its own `deps.edn` that bundles exactly
the components it needs.

**You pull in ONE package and it bundles every component that capability needs
(transitively).** You never assemble components by hand. Every package is
git-dep'd the same way — give it a lib name that matches the package and point
`:deps/root` at the project:

```clojure
;; in your project's deps.edn — pick the ONE package you need (table below)
obneyai/orc-evaluation                  ;; lib name = the package name
{:git/url "https://github.com/ObneyAI/orc.git"
 :git/sha "..."                         ;; pin to a reviewed commit
 :deps/root "projects/orc-evaluation"}  ;; <- the project that bundles it
```

> **Naming matters if you combine packages.** The lib name (the map key) is what
> Clojure's tools.deps uses to identify the dependency. Name each one after its
> package (`obneyai/orc-evaluation`, `obneyai/orc-ontology`, …) — not all
> `obneyai/orc` — so you can pull **more than one** in the same `deps.edn`
> without the keys colliding. The only time you combine packages today is
> ColBERT (see [orc-colbert](#orc-colbert)); every other capability lives in a
> single package.

## Package summary

| Package (`:deps/root`) | What you get | Pulls DJL? |
|---------|-------------|:----------:|
| **`projects/orc-service`** | The engine: behavior-tree DSL, runtime, idempotent restart recovery, streaming, bounded telemetry export, RLM (`:repl-researcher`), inline value storage, and the external-storage protocol | No |
| **`projects/orc-evaluation`** | Engine + LLM-as-judge evaluation (grounding, reasoning, completeness, instruction-following) | No |
| **`projects/orc-gepa`** | Engine + evaluation + GEPA prompt optimization (Pareto + reflective mutation) | No |
| **`projects/orc-ontology`** | Engine + general-purpose event-sourced concept graph, DJL embeddings, evolutionary builder, self-improving write-side | **Yes** (DJL, in-JVM) |
| **`projects/orc-colbert`** | The pure-JVM ColBERT signal — the optional Layer-5 late-interaction upgrade. Add alongside `orc-ontology`. | **Yes** (DJL OnnxRuntime, in-JVM; ~133MB model download on first use) |
| **`projects/orc-mcp-sheet-builder`** | Engine + dynamic tree generation from MCP tool schemas (Layer 8, standalone) | No |
| **`projects/orc`** | The umbrella — everything above. The full self-improving loop. | Yes |

> The bold name is both the **lib name** to use in your `deps.edn` (e.g.
> `obneyai/orc-evaluation`) and the **`:deps/root`** to point at (e.g.
> `projects/orc-evaluation`). Every non-leaf package bundles the engine
> (`orc-service` + its `langfuse` tracing layer) transitively — you never pull
> `orc-service` separately unless you want *only* the engine.

## orc-service

The Layer-0 engine. Behavior-tree DSL (`workflow`, `sequence`, `parallel`,
`fallback`, `map-each`, `llm`, `code`, `condition`, `delegate`, `repl-researcher`),
synchronous + streaming execution, idempotent recovery of abandoned leaf
frontiers, bounded failure-isolated telemetry export, event-sourced sheets,
versioning, and the generic file-store contract. Its runtime dependencies include the LLM-call
layer (ORC's SIO-backed `llm` component), structured logging (mulog), a safe Clojure interpreter (sci),
and Nippy value encoding. No model loading.

Canonical values remain inline by default. The engine includes the generic
file-store protocol used by `:orc/value-storage {:type :file-store}`, but this
lean package does not bundle a concrete local or S3 backend. Include the
corresponding component in a source/workspace deployment, or use the umbrella
package, which bundles both. See [Value Storage](VALUE-STORAGE.md).

```clojure
obneyai/orc-service {:git/url "https://github.com/ObneyAI/orc.git"
                     :git/sha "..." :deps/root "projects/orc-service"}
```

## orc-evaluation

The engine plus LLM-as-judge evaluation. Attach judges to any `:leaf` or
`:repl-researcher` node; scores emit as `:judge/score-emitted` events. The
Living-Description gate is resolved lazily, so **judges run with zero ontology
and zero DJL**.

```clojure
obneyai/orc-evaluation {:git/url "https://github.com/ObneyAI/orc.git"
                        :git/sha "..." :deps/root "projects/orc-evaluation"}
```

## orc-gepa

The engine plus evaluation plus GEPA instruction optimization. Optimizes the
`:instruction` on your static `:llm` nodes via Pareto-frontier selection and
reflective mutation, scored by the evaluation judges. Runs can resume from
durable model-call/evaluation boundaries, and completed winners can be applied
to an explicit source version to publish a new immutable workflow version. No
ontology.

```clojure
obneyai/orc-gepa {:git/url "https://github.com/ObneyAI/orc.git"
                  :git/sha "..." :deps/root "projects/orc-gepa"}
```

## orc-ontology

The engine plus the general-purpose ontology: an event-sourced concept graph,
DJL embeddings (in-JVM, `all-MiniLM-L6-v2` by default, any HuggingFace
sentence-transformer via `:model-id`), a supported custom graph lifecycle, the
evolutionary builder (ingest CSV/JSON/SQL/text or deterministic N-Triples RDF),
and the self-improving loop's write-side (consolidator, Living Descriptions,
classifier). Retrieval runs on **graph BFS + DJL
embeddings**. ColBERT is resolved lazily; add `orc-colbert` for the
third signal.

Every evolutionary public read is tenant-scoped through the caller context,
including source/concept/history/statistics queries. Learned descriptions and
source extractions retain model provenance when model evidence is available.
The public lifecycle API creates empty ontologies, validates concept/update/relationship
mutations, returns stable identities, and supports command-ID retry reconciliation.
`evolve` can add registered sources to those manually created ontologies.

```clojure
obneyai/orc-ontology {:git/url "https://github.com/ObneyAI/orc.git"
                      :git/sha "..." :deps/root "projects/orc-ontology"}
```

## orc-colbert

The pure-JVM ColBERT signal — the optional Layer-5 late-interaction upgrade. A
leaf package. Add it **alongside** `orc-ontology` to light up the third signal
in `hybrid-search`. It pulls DJL (api + HuggingFace tokenizers + OnnxRuntime
engine, all 0.31.1) and downloads the `answerai-colbert-small-v1` encoder
checkpoint (~133 MB) into a local cache on first use — no Python, no setup
(`-Dcolbert.model.path` overrides for air-gapped machines).

Because you pull *two* packages here, give them distinct lib names so the keys
don't collide:

```clojure
;; ontology + colbert together — distinct keys, same repo + sha
obneyai/orc-ontology {:git/url "https://github.com/ObneyAI/orc.git"
                      :git/sha "..." :deps/root "projects/orc-ontology"}
obneyai/orc-colbert  {:git/url "https://github.com/ObneyAI/orc.git"
                      :git/sha "..." :deps/root "projects/orc-colbert"}
```

## orc-mcp-sheet-builder

The engine plus the MCP Sheet Builder (Layer 8): connect to an MCP tool server,
analyze its schemas, and generate an ORC behavior tree. Standalone — no ontology,
no ColBERT, no model loading.

```clojure
obneyai/orc-mcp-sheet-builder {:git/url "https://github.com/ObneyAI/orc.git"
                               :git/sha "..." :deps/root "projects/orc-mcp-sheet-builder"}
```

## orc (umbrella)

Everything: engine + evaluation + gepa + ontology + colbert + mcp-sheet-builder,
plus the local and S3 file-store backends.
This is the package that gives you the **full self-improving loop** — there is no
separate "self-improving-loop" package because the loop is a *capability* that
emerges from `ontology` + `colbert` + `evaluation` running on the engine. Pull
the umbrella when you want all of it; pull the individual packages to stay lean.

```clojure
obneyai/orc {:git/url "https://github.com/ObneyAI/orc.git"
             :git/sha "..." :deps/root "projects/orc"}
```

## How the packages compose

```
orc-service ........ engine (Layer 0)            [base of every package]
  + evaluation ..... orc-evaluation              (Layer 1-2)
      + gepa ....... orc-gepa                     (Layer 3)
  + ontology ....... orc-ontology                 (Layers 4, 6 — DJL, in-JVM)
      + colbert .... orc-colbert                  (Layer 5 — DJL OnnxRuntime, in-JVM)
  + mcp ............ orc-mcp-sheet-builder         (Layer 8)

self-improving loop  = ontology + colbert + evaluation on the engine
                     = the orc umbrella, or those packages combined
```

See [COMPONENT-MAP.md](COMPONENT-MAP.md) for the full opt-in layer table and the
component dependency graph.
