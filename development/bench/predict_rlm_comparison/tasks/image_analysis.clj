(ns predict-rlm-comparison.tasks.image-analysis
  "image_analysis benchmark port — analyze a single rendered image with a
   natural-language query.

   Methodology mirrors predict-rlm:
     - same sample image (copied verbatim under references/, MIT)
     - same default query (copied verbatim from predict-rlm run.py)
     - goal-instruction copied verbatim from predict-rlm signature.py docstring

   Our addition: the runner pre-encodes the image and loads it into the
   blackboard under :image with schema [:string {:field-type :image}] so
   dscloj routes it as an OpenAI-style image_url content block on the
   sub-LLM call (no model-side base64-encoding required).

   This file is also a complete worked example of how to compose an ORC
   RLM benchmark — see the :task map at the bottom for the full shape
   (name, slug, model + sub-model, instruction, input/output schemas,
   input-loader, writes, evaluation-criteria, predict-rlm-reported
   metadata).

   Run from REPL:
     (require '[predict-rlm-comparison.tasks.image-analysis :as t])
     (require '[predict-rlm-comparison.runner :as r])
     (r/start!)
     (r/run! t/task)
     (r/stop!)

   Or via the standalone Clojure runner:
     clj -M:dev:test -m predict-rlm-comparison.run.image-analysis"
  (:require [ai.obney.orc.predict-rlm-image-tools.interface :as img-tools]))

(def ^:private image-path
  "development/bench/predict_rlm_comparison/references/predict-rlm/image_analysis/sample/input/screenshot.png")

(def ^:private default-query
  ;; Verbatim from predict-rlm examples/image_analysis/run.py DEFAULT_QUERY.
  "

    What letters appear in each image, and how many times does each letter appear? Always include: logo text, header address/phone/fax, header email, header website URL, \"Page N\" footers, etc.

    For each image:
    1. Extract the visible text multiple times (at least 2-3 extractions per image)
    2. Compare the extractions - if they differ, extract again until you get consistent results
    3. Only after you have consistent text extraction, count the letters programmatically (case insensitive)

    Use prompts like \"Return ONLY the exact text visible, nothing else.\"
    Do all counting and comparison in Python, not via predict().

    Treat uppercase and lowercase as the same letter (case-insensitive).
    Output the letter statistics in alphabetical order (A-Z).")

(def ^:private instruction
  ;; Port-cleanup notes (see references/predict-rlm/image_analysis/signature.py.txt
  ;; for the original verbatim source):
  ;;
  ;; predict-rlm's signature docstring is a Python procedural recipe ("load via
  ;; pathlib, call predict() with dspy.Image, gather with asyncio"). It conflates
  ;; (a) the end-goal — analyze + answer thoroughly — with (b) a tool list
  ;; specific to their Python-in-WASM runtime. Keeping it verbatim would inject
  ;; Python tool names into our prompt and bury the goal under their step list,
  ;; which our model takes as procedure rather than methodology.
  ;;
  ;; Port principle: keep the end-goal and quality bar verbatim; strip the
  ;; language-specific tool list and the "step 1/2/3/4" procedural framing.
  ;; Do not add tree-shape hints or methodology of our own. The model still
  ;; sees the verbatim user query below; methodology suggestions baked into
  ;; their default query are preserved there.
  "Analyze the provided image(s) and answer the query thoroughly.

   Your answer must fully address the query and capture every relevant detail
   visible in the image(s). Examine each image carefully — including logo
   text, header information (address, phone, fax, email, website URL),
   footers (e.g. \"Page N\"), paragraph body text, form labels, and any other
   visible text content. Be careful and complete.

   STRUCTURAL VERIFICATION REQUIREMENT: For high-confidence answers, the
   behavior tree you design and emit should include a separate verification
   node that re-reads the source independently and cross-checks the first
   pass. Single-pass extraction from a dense visual source reliably misses
   small details (a character here, a label there) — a second independent
   tree node that re-reads the source and reconciles with the first-pass
   output catches these. We are not telling you the exact tree shape — that
   is your design decision — but the final answer should reflect output that
   survives an independent second look at the source, not just the first
   impression. Use the behavior-tree primitives (sequence, llm, code) rather
   than coordinating multiple sub-calls inline as imperative Clojure code:
   the tree is the durable, observable record of the work that was done.

   Produce a single output key `:answer` (string) in the format the query
   requests.")

(def task
  {:name "Image Analysis (predict-rlm port)"
   :slug "image-analysis"
   :pattern "Single-image vision QA — letter-frequency extraction with consistency check"
   ;; Apples-to-apples model setup matching predict-rlm:
   ;;   main LM (Phase-1 researcher): openai/gpt-5.4
   ;;   sub-LM (Phase-2 :llm leaves):  openai/gpt-5.1-chat
   ;; Both available on OpenRouter (the gpt-5 family requires max_tokens >= 16
   ;; — our defaults satisfy that). Comment out these two lines to fall back
   ;; to the runner's default model (gemini-3-flash-preview).
   :model "openai/gpt-5.4"
   :sub-model "google/gemini-3-flash-preview"
   ;; NOTE: :available-code-nodes intentionally NOT set for this task —
   ;; we want to verify the model writes its OWN inline :code fns for
   ;; deterministic transforms (the inline-fn affordance was added with
   ;; the framework prompt update to advertise this option). If the
   ;; model still defaults to LLM-based counting without an advertised
   ;; counter, that's an instruction-tuning finding.
   :instruction instruction
   :input-schemas {:image [:string {:field-type :image}]
                   :query :string}
   :input-loader (fn []
                   {:image (img-tools/image->data-uri image-path)
                    :query default-query})
   :writes [:answer]
   :evaluation-criteria
   ["The :answer must demonstrably look at the image content (cite text visible in the screenshot)."
    "The :answer should attempt the letter-frequency task in A-Z order."
    "Trace JSONL should contain at least one sub-LLM call with an image_url content block."
    "Compare token / cost / duration against predict-rlm reported numbers in references/predict-rlm/image_analysis/sample/output/output.md."]
   :predict-rlm-reported
   ;; From references/predict-rlm/image_analysis/sample/output/output.md.
   {:main-lm-calls 4
    :main-lm-input-tokens 17048
    :main-lm-output-tokens 1821
    :sub-lm-calls 5
    :sub-lm-input-tokens 5723
    :sub-lm-output-tokens 1955
    :cost-usd 0.08
    :duration-sec 60}})
