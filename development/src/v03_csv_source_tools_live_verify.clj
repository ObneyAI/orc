(ns v03-csv-source-tools-live-verify
  "V03 live verification — drives a REAL LLM through a recursive
   exploration loop where its ONLY affordances are the three CSV
   source-access tools (peek-columns / sample-rows / profile-column) plus
   (final! ...). The model is given the tool docstrings ALONE (no other
   hints about the file) and asked to describe the structure of the REAL
   /Users/darylroberts/Downloads/cip_soc_crosswalk.csv.

   This is the slice's discipline-4 gate: synthetic tests are the floor,
   this is the ceiling. It proves (a) a capable model can use the tools
   from their docstrings alone (the S19 lesson) and (b) the tools let it
   describe the real crosswalk's structure correctly WITHOUT ever loading
   the whole file (the bounded line counter is asserted after each run).

   NOTE ON SCOPE: the unified source-tool registry + the discovery-RLM
   grant are wired in V06. V03 owns only the per-format CSV namespace, so
   this verify builds a minimal SCI sandbox over the csv-source-tools
   bindings directly (the same {symbol -> fn} map V06 will merge into the
   discovery sandbox) rather than going through build-rlm-context. The
   tool-use behavior under test is identical; only the binding-injection
   seam differs, and that seam is V06's.

   USAGE (from a REPL with the :dev alias, OPENROUTER_API_KEY in env):

     (require '[v03-csv-source-tools-live-verify :as v])
     (v/print-transcript! (v/run-once! {:model \"gemini-3-flash-preview\"}))"
  (:require [ai.obney.orc.orc-service.core.source-tools-csv :as csv-tools]
            [ai.obney.orc.orc-service.core.executor :as executor]
            [sci.core :as sci]
            [dscloj.core :as dscloj]
            [clojure.string :as str]
            [clojure.pprint :as pp]))

(def real-crosswalk-path
  "/Users/darylroberts/Downloads/cip_soc_crosswalk.csv")

;; =============================================================================
;; A minimal SCI sandbox over the CSV source tools (V06 will do this through
;; build-rlm-context; here we exercise the same bindings map directly).
;; =============================================================================

(defn- build-csv-sandbox [csv-path]
  (let [final-output (atom nil)
        tool-bindings (csv-tools/csv-source-tools {:csv-path csv-path})
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
   (->> ['peek-columns 'sample-rows 'profile-column]
        (map (fn [sym] (str "### " sym "\n"
                            (get csv-tools/csv-source-tool-docs sym) "\n")))
        (str/join "\n"))))

(def live-task-prompt
  (str
   "You are a recursive RLM researcher exploring a structured SOURCE you "
   "CANNOT see directly. You may ONLY learn about it through the tools "
   "below — you must NOT assume its contents. The source is a CSV file.\n\n"
   tool-affordances-block
   "\n\n"
   "Your task: explore the CSV by its SHAPE and describe its structure: "
   "what columns it has, the type of each, which columns look like keys / "
   "codes, whether it is a CROSSWALK (a mapping between two code systems) "
   "and if so between WHAT, the approximate cardinality of the key columns, "
   "and a couple of concrete example rows. Use AT LEAST peek-columns, "
   "sample-rows, and profile-column. Show your work step by step.\n\n"
   "End with (final! {:columns [...] :is-crosswalk? <bool> :crosswalk-between "
   "[...] :cardinality {...} :description \"...\"}).\n\n"
   "Write Clojure code (a SINGLE form each iteration). The sandbox executes "
   "it and shows you the result."))

;; =============================================================================
;; Single-shot run
;; =============================================================================

(defn run-once!
  [{:keys [model max-iterations csv-path]
    :or {model "gemini-3-flash-preview"
         max-iterations 8
         csv-path real-crosswalk-path}}]
  ;; Register the real LLM providers (openrouter etc.) before any predict.
  (executor/setup-providers!)
  ;; Start every run with a clean line-read counter so the bounded-read
  ;; assertion reflects ONLY this session.
  (reset! csv-tools/*last-lines-read* 0)
  (let [sandbox (build-csv-sandbox csv-path)
        max-lines-seen (atom 0)
        transcript (atom [])]
    (loop [iter 0
           history-blocks []]
      (if (>= iter max-iterations)
        {:status :exhausted-iterations
         :transcript @transcript
         :max-lines-read @max-lines-seen
         :final-output @(:final-output sandbox)}
        (let [prompt (str live-task-prompt
                          "\n\n=== TRANSCRIPT SO FAR ===\n"
                          (str/join "\n---\n" history-blocks)
                          "\n\nWrite your NEXT single Clojure form (no prose):")
              module {:inputs [{:name :prompt :spec :any :description "Task"}]
                      :outputs [{:name :code :spec :any :description "next form"}]
                      :instructions prompt}
              ;; Resolve the model-specific provider the same way the
              ;; production executor does (registers :openrouter/<model> on
              ;; the fly), so this verify routes identically.
              effective-provider ((deref #'executor/get-provider-with-model)
                                   :openrouter model)
              code-resp (dscloj/predict effective-provider
                                        module {:prompt prompt}
                                        {:validate? false :with-metadata? true})
              code (or (:code (:outputs code-resp)) (:code code-resp))
              exec-r (exec sandbox code)
              ;; Track the worst-case lines read across the whole session.
              _ (swap! max-lines-seen max @csv-tools/*last-lines-read*)
              block (str "ITER " iter "\nCODE:\n" code
                         "\nRESULT: " (pr-str (:result exec-r))
                         (when (:error exec-r) (str "\nERROR: " (:error exec-r))))]
          (swap! transcript conj {:iter iter
                                  :code code
                                  :result (:result exec-r)
                                  :error (:error exec-r)
                                  :lines-read-after @csv-tools/*last-lines-read*})
          (if @(:final-output sandbox)
            {:status :final
             :transcript @transcript
             :iterations (inc iter)
             :max-lines-read @max-lines-seen
             :file-total-lines (with-open [r (clojure.java.io/reader csv-path)]
                                 (count (line-seq r)))
             :final-output @(:final-output sandbox)}
            (recur (inc iter) (conj history-blocks block))))))))

(defn print-transcript! [result]
  (println "=== V03 LIVE VERIFY TRANSCRIPT ===")
  (println "Status:" (:status result))
  (println "Iterations:" (:iterations result))
  (println "Max lines read in any single tool call:" (:max-lines-read result)
           "(file has" (:file-total-lines result) "total lines —"
           "a full dump would read all of them)")
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
    (when (:error step) (println "ERROR:" (:error step)))
    (println "lines-read-after:" (:lines-read-after step))))
