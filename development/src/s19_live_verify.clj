(ns s19-live-verify
  "S19 live verification — exercises the seven ontology tools through a
   REAL recursive RLM session against a REAL Grain event-store, real
   embeddings, and a real LLM.

   This is the manual gate required by the slice's discipline 4:
   synthetic tests are the floor, this is the ceiling. Run it once
   before declaring S19 done; capture the transcript for the commit
   body.

   USAGE (from a REPL with :dev alias active):

     (require '[s19-live-verify :as v])
     (v/run-once! {:model \"gemini-3-flash-preview\"})

   The session:
   - Seeds a small two-section graph (directors+films in A, scholarships
     in B — same shape as the integration tests).
   - Builds a recursive RLM sandbox granted ONLY section A.
   - Drives a recursive-RLM iteration loop with a real LLM prompt that
     asks for a multi-step retrieval task requiring ≥4 of the seven
     tools to answer correctly.
   - Captures the full transcript (every iteration's prompt + model
     code + sandbox stdout + result) so the operator can review tool-use
     quality (the discipline-2 axis) — right tool, right arguments,
     grounded conclusions.

   ONLY run when OPENROUTER_API_KEY (or your preferred provider key) is
   set in the environment."
  (:require [ai.obney.orc.ontology.interface.schemas]
            [ai.obney.orc.ontology.core.commands :as cmd]
            [ai.obney.orc.ontology.test-helpers :as h]
            [ai.obney.orc.orc-service.core.rlm-sandbox :as rlm-sandbox]
            [ai.obney.orc.orc-service.core.sandbox-tools :as st]
            [dscloj.core :as dscloj]
            [clojure.string :as str]
            [clojure.pprint :as pp]))

;; =============================================================================
;; Seed corpus — small two-section fixture
;; =============================================================================

(def section-a-id #uuid "a1900000-0000-0000-0000-00000000000a")
(def section-b-id #uuid "b1900000-0000-0000-0000-00000000000b")

(defn- seed-concept! [ctx ontology-id uri label scope]
  (h/run-and-apply! ctx
    (fn [c]
      (cmd/ontology-create-concept
        (assoc c :command {:ontology-id ontology-id
                           :uri uri :label label
                           :description (str label " (" ontology-id ")")
                           :scope scope :broader [] :indicators []})))))

(defn- seed-rel! [ctx src pred tgt props]
  (h/run-and-apply! ctx
    (fn [c]
      (cmd/ontology-create-relationship
        (assoc c :command (merge {:source-uri src :predicate pred :target-uri tgt
                                  :properties {}}
                                 props))))))

(defn seed-fixture! [ctx]
  (doseq [[uri label] [["concept:dir/jane-roe" "Jane Roe"]
                       ["concept:dir/john-doe" "John Doe"]
                       ["concept:dir/sam-smith" "Sam Smith"]
                       ["concept:film/red-dawn" "Red Dawn"]
                       ["concept:film/blue-tomorrow" "Blue Tomorrow"]
                       ["concept:role/director" "Director"]]]
    (seed-concept! ctx section-a-id uri label :custom))
  (seed-rel! ctx "concept:dir/jane-roe" "directed" "concept:film/red-dawn"
             {:confidence-class :extracted})
  (seed-rel! ctx "concept:dir/john-doe" "directed" "concept:film/blue-tomorrow"
             {:confidence-class :extracted})
  ;; sam-smith retired — has a retired edge.
  (seed-rel! ctx "concept:dir/sam-smith" "retired" "true"
             {:confidence-class :extracted})
  ;; section B content — should never surface from an A-granted sandbox.
  (doseq [[uri label] [["concept:scholarship/dean" "Dean's Scholarship"]
                       ["concept:program/cs" "Computer Science"]]]
    (seed-concept! ctx section-b-id uri label :custom)))

;; =============================================================================
;; The live RLM prompt
;; =============================================================================

(def live-task-prompt
  (str
    "You are a recursive RLM researcher with access to ONLY a small ontology graph "
    "scoped to a single section of an ontology. You have these tools available "
    "(call them like any Clojure fn):\n\n"
    "  graph-search, neighborhood, get-concept, exists?, absent-in-graph?, "
    "filter-by-label-pattern, classify-task, classify-behaviors\n\n"
    "(meta graph-search) etc. returns the tool's docstring.\n\n"
    "Your task: find all DIRECTORS (concepts whose label or alt-label mentions "
    "director-like names, OR who have a \"directed\" edge to a film) who have "
    "NOT retired. You must use AT LEAST FOUR of the tools above to do this — "
    "show your work step by step.\n\n"
    "End with (final! {:active-directors [...] :reasoning \"...\"}) where "
    ":active-directors is a vector of concept-uri strings.\n\n"
    "Write Clojure code (single forms each iteration). The sandbox executes "
    "them and shows you the result."))

;; =============================================================================
;; Single-shot run
;; =============================================================================

(defn run-once!
  "Run the live verify ONCE. Returns a map with the full transcript."
  [{:keys [model max-iterations]
    :or {model "gemini-3-flash-preview"
         max-iterations 8}}]
  (h/with-test-context [ctx]
    (seed-fixture! ctx)
    (let [rlm-ctx (rlm-sandbox/build-rlm-context
                    {:provider :openrouter
                     :blackboard {}
                     :declared-writes [:active-directors :reasoning]
                     :recursive? true
                     :event-store (:event-store ctx)
                     :tenant-id (:tenant-id ctx)
                     :cache (:cache ctx)
                     :granted-ontology-id section-a-id})
          transcript (atom [])]
      (loop [iter 0
             history-blocks []]
        (if (>= iter max-iterations)
          {:status :exhausted-iterations
           :transcript @transcript
           :final-output @(:final-output rlm-ctx)}
          (let [prompt (str live-task-prompt
                            "\n\n=== TRANSCRIPT SO FAR ===\n"
                            (str/join "\n---\n" history-blocks)
                            "\n\nWrite your NEXT single Clojure form (no explanation):")
                module {:inputs [{:name :prompt :spec :any :description "Task"}]
                        :outputs [{:name :code :spec :any :description "next form"}]
                        :instructions prompt}
                code-resp (dscloj/predict (keyword "openrouter" model)
                                          module {:prompt prompt}
                                          {:validate? false :with-metadata? true})
                code (or (:code (:outputs code-resp)) (:code code-resp))
                exec (rlm-sandbox/execute-rlm-code rlm-ctx code)
                block (str "ITER " iter "\nCODE:\n" code
                           "\nRESULT: " (:result exec)
                           (when (:error exec) (str "\nERROR: " (:error exec))))]
            (swap! transcript conj {:iter iter
                                    :code code
                                    :result (:result exec)
                                    :error (:error exec)
                                    :final-output (:final-output exec)})
            (if (:final-output exec)
              {:status :final
               :transcript @transcript
               :final-output (:final-output exec)
               :iterations (inc iter)}
              (recur (inc iter) (conj history-blocks block)))))))))

(defn print-transcript! [result]
  (println "=== S19 LIVE VERIFY TRANSCRIPT ===")
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
    (println (:result step))
    (when (:error step) (println "ERROR:" (:error step)))))
