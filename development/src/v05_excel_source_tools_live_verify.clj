(ns v05-excel-source-tools-live-verify
  "V05 live verification — drives a REAL LLM through a recursive exploration
   loop where its ONLY affordances are the four Excel source-access tools
   (list-sheets / sheet-columns / sample-rows / excel-dir-sheets) plus
   (final! ...). The model is given the tool docstrings ALONE (no other hints
   about the workbook) and asked to describe the structure of the REAL
   /Users/darylroberts/Downloads/pseo_la.xlsx (and, optionally, the O*NET
   multi-file directory).

   This is the slice's discipline-4 gate: synthetic tests are the floor, this
   is the ceiling. It proves (a) a capable model can use the Excel tools from
   their docstrings alone and (b) the tools let it describe the real PSEO
   workbook's sheets/columns correctly WITHOUT ever loading the 119 MB sheet.

   NOTE ON SCOPE: the unified source-tool registry + the discovery-RLM grant
   are wired in V06. V05 owns only the per-format Excel namespace, so this
   verify builds a minimal SCI sandbox over the excel-source-tools bindings
   directly (the same {symbol -> fn} map V06 will merge into the discovery
   sandbox) rather than going through build-rlm-context. The tool-use behavior
   under test is identical; only the binding-injection seam differs, and that
   seam is V06's.

   USAGE (from a REPL with the :dev alias, OPENROUTER_API_KEY in env):

     (require '[v05-excel-source-tools-live-verify :as v])
     (v/print-transcript! (v/run-once! {:model \"gemini-3-flash-preview\"}))"
  (:require [ai.obney.orc.orc-service.core.source-tools-excel :as ex]
            [ai.obney.orc.orc-service.core.executor :as executor]
            [sci.core :as sci]
            [dscloj.core :as dscloj]
            [clojure.string :as str]
            [clojure.pprint :as pp]))

(def real-pseo-path "/Users/darylroberts/Downloads/pseo_la.xlsx")

;; =============================================================================
;; A minimal SCI sandbox over the Excel source tools (V06 will do this through
;; build-rlm-context; here we exercise the same bindings map directly).
;; =============================================================================

(defn- build-excel-sandbox []
  (let [final-output (atom nil)
        tool-bindings (ex/excel-source-tools)
        bindings (merge tool-bindings
                        {'final! (fn [m] (reset! final-output m) m)})
        ctx (sci/init {:bindings bindings})]
    {:sci-ctx ctx
     :final-output final-output}))

(defn- exec [{:keys [sci-ctx]} code]
  (try
    {:result (sci/eval-string* sci-ctx code)}
    (catch Exception e
      {:error (.getMessage e)})))

;; =============================================================================
;; The tool-affordances block — docstrings ALONE (the S20-card analogue)
;; =============================================================================

(def tool-affordances-block
  (str
   "TOOLS — each with PURPOSE / EXAMPLE / RETURNS. Call them like Clojure fns:\n\n"
   (->> ['list-sheets 'sheet-columns 'sample-rows 'excel-dir-sheets]
        (map (fn [sym] (str "### " sym "\n"
                            (get ex/excel-source-tool-docs sym) "\n")))
        (str/join "\n"))))

(defn live-task-prompt [xlsx-path]
  (str
   "You are a recursive RLM researcher exploring a structured SOURCE you "
   "CANNOT see directly. You may ONLY learn about it through the tools below "
   "— you must NOT assume its contents. The source is an Excel workbook at "
   "the path \"" xlsx-path "\".\n\n"
   tool-affordances-block
   "\n\n"
   "Your task: explore the workbook by its SHAPE and describe its structure: "
   "what sheets it has, and for EACH sheet what columns it has (note that the "
   "header may NOT be the first row — title/source lines often precede it), "
   "the type of each column, which columns look like keys / codes, what the "
   "workbook appears to be ABOUT, and a couple of concrete example rows. Use "
   "list-sheets, then sheet-columns and sample-rows on each sheet. Show your "
   "work step by step.\n\n"
   "End with (final! {:sheets [{:name \"...\" :columns [...] :header-row N} ...] "
   ":about \"...\" :key-columns [...] :description \"...\"}).\n\n"
   "Write Clojure code (a SINGLE form each iteration). The sandbox executes it "
   "and shows you the result."))

;; =============================================================================
;; Single-shot run
;; =============================================================================

(defn run-once!
  [{:keys [model max-iterations xlsx-path]
    :or {model "gemini-3-flash-preview"
         max-iterations 10
         xlsx-path real-pseo-path}}]
  (executor/setup-providers!)
  (let [sandbox (build-excel-sandbox)
        prompt-base (live-task-prompt xlsx-path)
        transcript (atom [])]
    (loop [iter 0
           history-blocks []]
      (if (>= iter max-iterations)
        {:status :exhausted-iterations
         :transcript @transcript
         :final-output @(:final-output sandbox)}
        (let [prompt (str prompt-base
                          "\n\n=== TRANSCRIPT SO FAR ===\n"
                          (str/join "\n---\n" history-blocks)
                          "\n\nYou MUST emit exactly one Clojure form this turn — "
                          "either a tool call or (final! {...}) when you have "
                          "enough to describe the workbook. Do NOT return an "
                          "empty answer. Write your NEXT single Clojure form "
                          "(no prose):")
              module {:inputs [{:name :prompt :spec :any :description "Task"}]
                      :outputs [{:name :code :spec :any :description "next form"}]
                      :instructions prompt}
              effective-provider ((deref #'executor/get-provider-with-model)
                                   :openrouter model)
              code-resp (dscloj/predict effective-provider
                                        module {:prompt prompt}
                                        {:validate? false :with-metadata? true})
              code (or (:code (:outputs code-resp)) (:code code-resp))
              ;; Flash occasionally emits an empty/whitespace turn mid-task
              ;; (it "thinks" instead of acting). Treat a blank turn as a
              ;; no-op rather than executing nil; the loop nudges it again.
              blank? (or (nil? code) (str/blank? (str code)))
              exec-r (if blank?
                       {:result nil :blank? true}
                       (exec sandbox code))
              block (str "ITER " iter "\nCODE:\n" code
                         "\nRESULT: " (pr-str (:result exec-r))
                         (when (:error exec-r) (str "\nERROR: " (:error exec-r))))]
          (swap! transcript conj {:iter iter
                                  :code code
                                  :result (:result exec-r)
                                  :error (:error exec-r)})
          (if @(:final-output sandbox)
            {:status :final
             :transcript @transcript
             :iterations (inc iter)
             :final-output @(:final-output sandbox)}
            (recur (inc iter) (conj history-blocks block))))))))

(defn print-transcript! [result]
  (println "=== V05 LIVE VERIFY TRANSCRIPT ===")
  (println "Status:" (:status result))
  (println "Iterations:" (:iterations result))
  (println "Final output:")
  (pp/pprint (:final-output result))
  (println "\n=== STEP-BY-STEP ===")
  (doseq [step (:transcript result)]
    (println "----")
    (println "Iter" (:iter step))
    (println "CODE:")
    (println (:code step))
    (println "RESULT:")
    (pp/pprint (:result step))
    (when (:error step) (println "ERROR:" (:error step)))))
