(ns driver-demo
  "End-to-end driver loop demo against examples/document_analysis.

   Verifies items 1–3 from RLM-DEEP-ANALYSIS.md Part 8 / plan:

     1. Take the document_analysis Style A pipeline.
     2. Degrade the synthesize node's instruction.
     3. Run a driver session with a small eval-set + judges.
     4. Confirm the driver observes the regression, emits whole-tree
        forms, runs ticks, scores improvements, and re-publishes a
        Sheet whose judge scores recover.

   Run from nREPL — each step prints diagnostics.

     (require '[driver-demo :as demo] :reload)
     (demo/setup!)
     (demo/baseline-run!)
     (demo/degrade!)
     (demo/run-driver-loop!)
     (demo/teardown!)"
  (:require [clojure.pprint :as pp]
            [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.orc.orc-service.interface :as orc]
            [ai.obney.orc.orc-service.core.dsl :as dsl]
            [ai.obney.orc.examples.document-analysis.pipeline :as pipeline]
            [ai.obney.orc.examples.document-analysis.schemas :as schemas]
            [ai.obney.orc.workflow-driver.interface :as driver]))

;; -----------------------------------------------------------------------------
;; Test corpus
;; -----------------------------------------------------------------------------

(def sample-pdf
  "/Users/cam/Documents/code/obneyai/orc/examples/document_analysis/sample/input/YYJ-2025-Parking-Management-RFP.pdf")

(def eval-set
  "One eval item — the existing RFP — with concrete extraction criteria
   the synthesize node should honor. Kept small so iterations are fast."
  [{:name "yyj-rfp-2025"
    :inputs {:documents [sample-pdf]
             :criteria
             (str "Extract a structured analysis with these sections:\n"
                  "  - Executive Summary (3-5 sentences)\n"
                  "  - Key Dates (every important date in the RFP)\n"
                  "  - Entities (named organizations / people / roles)\n"
                  "  - Financial Information (any dollar figures, "
                  "    rates, or contractual financial terms).\n\n"
                  "Stay grounded — do not invent dates or entities not "
                  "present in the source text.")}}])

;; -----------------------------------------------------------------------------
;; Mutable demo state
;; -----------------------------------------------------------------------------

(def state (atom {}))

(defn setup!
  "Spin up an async test context, build the document_analysis pipeline
   into it, and store both in the demo state. Idempotent."
  []
  (when (:ctx @state)
    (h/stop-async-context (:ctx @state)))
  (orc/setup-providers!)
  (let [ctx (h/create-async-test-context)
        sheet-id (orc/build-workflow! ctx pipeline/workflow)]
    (reset! state {:ctx ctx :sheet-id sheet-id})
    (println "Demo set up. Sheet ID:" sheet-id)
    sheet-id))

(defn teardown!
  []
  (when-let [ctx (:ctx @state)]
    (h/stop-async-context ctx))
  (reset! state {})
  :torn-down)

;; -----------------------------------------------------------------------------
;; Baseline + degrade + recover
;; -----------------------------------------------------------------------------

(defn baseline-run!
  "Sanity check: run the pristine pipeline once with judges. Confirms
   the eval set + judge wiring is sound before we deliberately break
   anything."
  []
  (let [{:keys [ctx sheet-id]} @state]
    (println "Running baseline eval-set against pristine pipeline…")
    (let [report (driver/run-eval-set! ctx sheet-id eval-set
                   {:tick-timeout-ms 120000
                    :judges [:grounding :completeness]})]
      (println "Pass-rate:    " (:pass-rate report))
      (println "Avg judge:    " (:avg-judge-score report))
      (println "Per-item:")
      (doseq [r (:results report)]
        (println "  " (:name r) "→" (:status r)
                 "duration=" (:duration-ms r) "ms"
                 "judge=" (some-> r :judge :avg-score)))
      report)))

(def vague-instruction
  "Synthesize.")

(def broken-instruction
  "Combined regression: vague instruction PLUS we drop :criteria from
   :reads, so the LLM literally cannot see what sections the analysis
   should produce. Judges should mark this down on completeness."
  "Output a brief summary.")

(defn degrade!
  "Replace the 'synthesize' node's instruction with something useless.
   Mutation goes through orc's normal command path so the change is
   durable in the event store."
  []
  (let [{:keys [ctx sheet-id]} @state
        nodes (orc/get-nodes-by-id ctx sheet-id)
        synth (->> nodes vals (filter #(= "synthesize" (:name %))) first)]
    (when-not synth
      (throw (ex-info "synthesize node not found — call setup! first" {})))
    ;; Use build-workflow! with the same workflow but a vague synthesize
    ;; instruction. Stable v5 IDs preserve node identity.
    (orc/build-workflow! ctx
      (dsl/workflow "document-analysis-pipeline"
        (dsl/blackboard schemas/blackboard)
        (dsl/sequence "main"
          (dsl/code "survey"
            :fn "ai.obney.orc.examples.document-analysis.pipeline/survey"
            :reads [:documents]
            :writes [:document-meta])
          (dsl/map-each "summarize-each"
            :from :document-meta :as :document :into :doc-summaries :parallel 3
            (dsl/llm "summarize-doc"
              :model "google/gemini-2.5-flash"
              :instruction
              (str "Summarize this document for an analyst. Be concise and grounded.\n\n"
                   "Pull out: any deadlines or key dates, named entities (people / orgs / roles), "
                   "and the main subject matter.\n\n"
                   "Do NOT invent details that aren't in the text.")
              :reads [:document :criteria]
              :writes [:doc-summary]))
          (dsl/llm "synthesize"
            :model "google/gemini-2.5-flash"
            :instruction broken-instruction
            ;; DROPPED :criteria — synthesize literally cannot see the
            ;; criteria sections the analysis is supposed to produce.
            :reads [:doc-summaries]
            :writes [:analysis]
            :judges ["grounding"])
          (dsl/code "render-docx"
            :fn "ai.obney.orc.examples.document-analysis.pipeline/render-docx"
            :reads [:analysis]
            :writes [:docx-report]))))
    (println "Degraded synthesize instruction →" vague-instruction)
    :ok))

(defn run-driver-loop!
  "Hand the degraded sheet to the driver agent. It should spot the
   bad synthesize instruction (because the judge scores will be lower
   on a one-word instruction) and emit a refinement that brings the
   score back."
  []
  (let [{:keys [ctx sheet-id]} @state
        objective
        (str "The 'synthesize' node's instruction is too vague — it just says \""
             vague-instruction
             "\". Strengthen it so the analysis stays grounded in the per-document "
             "summaries, follows the criteria's section structure (Executive Summary, "
             "Key Dates, Entities, Financial Information), and forbids fabrication. "
             "Keep the node name and model. Do not touch other nodes.")]
    (println "Running driver loop with judges [:grounding :completeness]…")
    (let [t0 (System/currentTimeMillis)
          result (driver/run-driver-loop! ctx
                   {:sheet-id sheet-id
                    :objective objective
                    :eval-set eval-set
                    :judges [:grounding :completeness]
                    :max-turns 3
                    :min-pass-rate 1.0
                    :min-judge-score 0.7
                    :model "google/gemini-2.5-flash"
                    :tick-timeout-ms 120000
                    :description "driver-demo recovery"})
          elapsed (- (System/currentTimeMillis) t0)]
      (println "── Driver result ──")
      (println "status:        " (:status result))
      (when (:version-number result)
        (println "version:       " (:version-number result)))
      (when (:reason result)
        (println "reason:        " (:reason result)))
      (println "turns:         " (count (:turns result)))
      (println "elapsed:       " elapsed "ms")
      (println "tokens:        " (:total-tokens (:usage result)))
      (println "final pass:    " (get-in result [:final-eval :pass-rate]))
      (println "final judge:   " (get-in result [:final-eval :avg-judge-score]))
      (println)
      (doseq [t (:turns result)]
        (println "── Turn" (:turn t) "──")
        (println "  decision:    " (:decision t))
        (println "  submit:      " (get-in t [:submit :status]))
        (when-let [d (get-in t [:submit :diff])]
          (println "  diff modified:" (mapv :name (:modified d))))
        (when (:eval t)
          (println "  pass-rate:   " (get-in t [:eval :pass-rate]))
          (println "  judge-score: " (get-in t [:eval :avg-judge-score]))))
      result)))
