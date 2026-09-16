(ns ai.obney.orc.orc-service.rr22-offered-pattern-whole-test
  "RR-22 — contract tests for `OfferedPatternsAreUsable` at the R-Inject seam:
   a pattern reaches the model WHOLE (never truncated — ratified: no emergency
   bound without a measured incident and a grill decision) and names the keys
   it reads and writes, as advice the model may adapt, never as an instruction
   to execute it. Same public seam as `r_inject_classifier_context_test`:
   `tp/apply-r05-classifier-context` with `ontology/get-description` redefined."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [ai.obney.orc.orc-service.core.todo-processors :as tp]
            [ai.obney.orc.ontology.interface :as ontology]))

(defn- mk-node [payload]
  {:id (random-uuid) :type :repl-researcher :name "rr22-node"
   :instruction "Summarize the document into :summary."
   :context {:tree-id (get-in payload [:structural :assigned-tree-id])
             :r05-classifier payload}})

(defn- payload-for [behavior-id]
  {:structural {:assigned-tree-id (random-uuid) :confidence 0.9 :was-fresh-mint? false
                :reasoning "fits" :top-candidates [] :rerank-fallback? false}
   :behavioral {:behaviors [{:behavior-id behavior-id :confidence 0.95 :was-fresh-mint? false
                             :reasoning "clear fit" :rerank-source :reranker}]
                :rerank-fallback? false}})

(def ^:private long-pattern
  ;; ~6,000 characters of exact, parseable source: forty :llm stages then a final.
  (str "[:sequence\n"
       (str/join "\n" (for [i (range 40)]
                        (format "  [:llm {:reads [:stage-%d] :writes [:stage-%d] :instruction \"refine step %d of the long worked example, keeping every intermediate finding explicit\"}]"
                                i (inc i) i)))
       "\n  [:final {:keys [:stage-40]}]]"))

(defn- body-with [pattern extra]
  {:summary "Long worked pattern." :capabilities ["long"] :weaknesses [] :representative-uses ["x"] :avoid-when []
   :version 1 :consolidated-from-event-count 1
   :strengths [(merge {:trait "emits the long-shape worked tree for tasks of this class"
                       :confidence 0.8 :evidence-count 3 :recommended-pattern pattern}
                      extra)]})

(defn- render [body]
  (let [behavior-id (random-uuid)]
    (:instruction
     (with-redefs [ontology/get-description (fn [_ctx _granularity target-id]
                                              (when (= target-id behavior-id) body))]
       (tp/apply-r05-classifier-context (mk-node (payload-for behavior-id)) {})))))

(deftest an-offered-pattern-is-never-truncated
  (testing "a 6,000-character pattern reaches the model whole"
    (is (> (count long-pattern) 5000) "sanity: the fixture is longer than any historical cap")
    (let [instruction (render (body-with long-pattern {}))]
      (is (str/includes? instruction long-pattern) "the exact, complete source is in the prompt")
      (is (not (str/includes? instruction "[truncated]")) "no truncation marker of any kind"))))

(deftest the-rendered-pattern-names-its-key-bindings
  (testing "the prompt states what the pattern reads and writes, as advice to rebind, not an order to run"
    (let [instruction (render (body-with "[:sequence [:llm {:reads [:doc] :writes [:summary]}] [:final {:keys [:summary]}]]"
                                         {:pattern-reads [:doc] :pattern-writes [:summary] :pattern-outputs [:summary]}))]
      (is (re-find #"(?i)reads:\s*:doc" instruction))
      (is (re-find #"(?i)writes:\s*:summary" instruction))
      (is (re-find #"(?i)rebind|adapt" instruction) "the bindings are offered for rebinding")
      (is (not (re-find #"(?i)must (run|execute|use) this" instruction)) "never a mandate"))))
