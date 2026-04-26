# image_analysis

Port of [predict-rlm/examples/image_analysis](https://github.com/Trampoline-AI/predict-rlm/tree/main/examples/image_analysis).

**Original task** (verbatim from the predict-rlm signature docstring):

> Analyze multiple images and answer the query about them.
> 1. List the image files available in the input directory.
> 2. Load each image as a base64 data URI.
> 3. Use predict() with dspy.Image typed inputs to analyze the images in the
>    context of the query. Process multiple images in parallel with
>    asyncio.gather() if there are several.
> 4. Synthesize the findings into a single answer that addresses the query
>    across all images.

**Inputs:** vector of image file paths (PNG/JPG/WEBP) + a query string
**Outputs:** a single text answer

## Files

- `schemas.clj`  — Malli blackboard schemas (no Pydantic equivalent — this
  example has no `schema.py`)
- `pipeline.clj` — Style A workflow (load → map-each analyze → synthesize)
- `agentic.clj`  — Style B workflow (single repl-researcher with `:rlm true`)
- `service.clj`  — `(execute-pipeline ctx inputs)` and `(execute-agentic ctx inputs)`
- `run.clj`      — REPL-runnable entry points
- `sample/input/` — sample image(s) copied from predict-rlm

## Skills

None. Image data URIs are constructed with stock Java + Clojure
(`java.util.Base64`).

## Running

```clojure
(require '[ai.obney.orc.examples.image-analysis.run :as run])
(run/run-pipeline)
(run/run-agentic)
```
