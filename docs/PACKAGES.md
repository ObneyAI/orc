# Packages

ORC is published as standalone packages from a single Polylith repo — the same
model [grain](https://github.com/ObneyAI/grain) uses. **Pull in only the package
you need** to keep your dependency footprint (and JVM/Python weight) minimal.
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

| Package (`:deps/root`) | What you get | Pulls DJL? | Pulls Python? |
|---------|-------------|:----------:|:-------------:|
| **`projects/orc-service`** | The engine: behavior-tree DSL, runtime, event-sourced execution, streaming, RLM (`:repl-researcher`) | No | No |
| **`projects/orc-evaluation`** | Engine + LLM-as-judge evaluation (grounding, reasoning, completeness, instruction-following) | No | No |
| **`projects/orc-gepa`** | Engine + evaluation + GEPA prompt optimization (Pareto + reflective mutation) | No | No |
| **`projects/orc-ontology`** | Engine + general-purpose event-sourced concept graph, DJL embeddings, evolutionary builder, self-improving write-side | **Yes** (DJL, in-JVM) | No |
| **`projects/orc-colbert`** | ColBERT late-interaction retrieval — the optional Layer-5 signal. Add alongside `orc-ontology`. | No | **Yes** (`.venv-colbert`) |
| **`projects/orc-mcp-sheet-builder`** | Engine + dynamic tree generation from MCP tool schemas (Layer 8, standalone) | No | No |
| **`projects/orc`** | The umbrella — everything above. The full self-improving loop. | Yes | Yes |

> The bold name is both the **lib name** to use in your `deps.edn` (e.g.
> `obneyai/orc-evaluation`) and the **`:deps/root`** to point at (e.g.
> `projects/orc-evaluation`). Every non-leaf package bundles the engine
> (`orc-service` + its `langfuse` tracing layer) transitively — you never pull
> `orc-service` separately unless you want *only* the engine.

## orc-service

The Layer-0 engine. Behavior-tree DSL (`workflow`, `sequence`, `parallel`,
`fallback`, `map-each`, `llm`, `code`, `condition`, `delegate`, `repl-researcher`),
synchronous + streaming execution, event-sourced sheets, versioning. Three
libraries only: an LLM-call layer (DSCloj), structured logging (mulog), and a
safe Clojure interpreter (sci). No model loading, no Python.

```clojure
obneyai/orc-service {:git/url "https://github.com/ObneyAI/orc.git"
                     :git/sha "..." :deps/root "projects/orc-service"}
```

## orc-evaluation

The engine plus LLM-as-judge evaluation. Attach judges to any `:leaf` or
`:repl-researcher` node; scores emit as `:judge/score-emitted` events. The
Living-Description gate is resolved lazily, so **judges run with zero ontology,
zero DJL, zero Python**.

```clojure
obneyai/orc-evaluation {:git/url "https://github.com/ObneyAI/orc.git"
                        :git/sha "..." :deps/root "projects/orc-evaluation"}
```

## orc-gepa

The engine plus evaluation plus GEPA instruction optimization. Optimizes the
`:instruction` on your static `:llm` nodes via Pareto-frontier selection and
reflective mutation, scored by the evaluation judges. No ontology, no Python.

```clojure
obneyai/orc-gepa {:git/url "https://github.com/ObneyAI/orc.git"
                  :git/sha "..." :deps/root "projects/orc-gepa"}
```

## orc-ontology

The engine plus the general-purpose ontology: an event-sourced concept graph,
DJL embeddings (in-JVM, `all-MiniLM-L6-v2` by default, any HuggingFace
sentence-transformer via `:model-id`), the evolutionary builder (ingest
CSV/JSON/SQL/text), and the self-improving loop's write-side (consolidator,
Living Descriptions, classifier). Retrieval runs on **graph BFS + DJL
embeddings** — no Python. ColBERT is resolved lazily; add `orc-colbert` for the
third signal.

```clojure
obneyai/orc-ontology {:git/url "https://github.com/ObneyAI/orc.git"
                      :git/sha "..." :deps/root "projects/orc-ontology"}
```

## orc-colbert

ColBERT late-interaction retrieval — the optional Layer-5 upgrade. A leaf package.
Add it **alongside** `orc-ontology` to light up the third signal in
`hybrid-search`. This is the one package that requires a Python environment
(`.venv-colbert`).

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
no ColBERT, no Python.

```clojure
obneyai/orc-mcp-sheet-builder {:git/url "https://github.com/ObneyAI/orc.git"
                               :git/sha "..." :deps/root "projects/orc-mcp-sheet-builder"}
```

## orc (umbrella)

Everything: engine + evaluation + gepa + ontology + colbert + mcp-sheet-builder.
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
  + ontology ....... orc-ontology                 (Layers 4, 6 — DJL, no Python)
      + colbert .... orc-colbert                  (Layer 5 — Python)
  + mcp ............ orc-mcp-sheet-builder         (Layer 8)

self-improving loop  = ontology + colbert + evaluation on the engine
                     = the orc umbrella, or those packages combined
```

See [COMPONENT-MAP.md](COMPONENT-MAP.md) for the full opt-in layer table and the
component dependency graph.
