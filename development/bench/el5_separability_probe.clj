(ns el5-separability-probe
  "THROWAWAY: prove (or refute) that SOME (margin, scale, cap) separates case (1)
   [refactor force-fit fires] from cases (2)(3)(4) [no false positive], using
   REAL DJL embeddings. If the refactor force-fit candidate's (cos-avoid - cos-good)
   is <= the case-(3) zero-FP candidate's (cos-avoid - cos-good), NO margin can
   fire (1) without also firing (3) — the escalation trigger.

   Run: clojure -M:dev -m el5-separability-probe"
  (:require [ai.obney.orc.ontology.core.embedding :as emb]))

(def refactor-task
  "INSTRUCTION:\nRefactor the order service to extract a pure pricing helper from the request handler, preserving existing behavior and keeping all current tests green.\n\nREADS: :user-message :active-plan :workspace-root\nWRITES: :assistant-response\nMCP-TOOLS: shell/exec fs/read fs/list\nBROWSER-TOOLS: (none)")

;; Case (1): rename-move-symbol on the refactor task (the force-fit we must fire).
(def rename-avoid
  ["the task changes behavior or adds functionality — that is code-building, not a behavior-preserving rename/move"
   "the change is a data reshape rather than an identity refactor of code symbols — that is the Transformation behavior"
   "the task is to extract a helper, pull out a function, or otherwise refactor/restructure code — that is code-building/refactor, NOT a pure identity rename or move of an existing symbol"
   "the symbol is referenced beyond the file that defines it"
   "the task is strictly a rename/move with behavior preserved"])
(def rename-good
  ["Rename-move-symbol is a behavior-preserving, EXHAUSTIVE cross-file identity refactor — rename a function everywhere, move a symbol to another namespace — changing NOTHING else."
   "the symbol is referenced from multiple files including tests"
   "a build or test command exists to confirm exhaustiveness and behavior preservation"])

;; Case (3): a web-search behavior on a plain web-search task (must NOT fire).
(def websearch-task
  "INSTRUCTION:\nSearch the web for the latest documentation on the payment API and summarize what you find.\n\nREADS: :user-message\nWRITES: :assistant-response\nMCP-TOOLS: web/search\nBROWSER-TOOLS: (none)")
(def websearch-avoid
  ["the web search requires special elevated permissions or authenticated access the agent does not hold"
   "the task is purely computational with no external lookup need"])
(def websearch-good
  ["Web-search gathers fresh external information by issuing search queries and reading results to ground downstream work."
   "the task needs current external information not already in context"])

(defn max-cos [task-emb strings]
  (->> strings
       (keep (fn [s] (when (seq s) (emb/cosine-similarity task-emb (emb/embed-text s)))))
       (apply max)))

(defn -main [& _]
  (let [r-emb (emb/embed-text refactor-task)
        w-emb (emb/embed-text websearch-task)
        r-avoid (max-cos r-emb rename-avoid)
        r-good  (max-cos r-emb rename-good)
        r-diff  (- r-avoid r-good)
        w-avoid (max-cos w-emb websearch-avoid)
        w-good  (max-cos w-emb websearch-good)
        w-diff  (- w-avoid w-good)]
    (println "\n=== EL-5 SEPARABILITY PROBE (real DJL embeddings) ===")
    (println (format "CASE (1) refactor force-fit [MUST FIRE]:  cos-avoid=%.3f cos-good=%.3f  diff=%+.3f" r-avoid r-good r-diff))
    (println (format "CASE (3) web-search own-domain [MUST=0]:  cos-avoid=%.3f cos-good=%.3f  diff=%+.3f" w-avoid w-good w-diff))
    (println "")
    (println "A margin M fires (1) iff diff(1) > M ; keeps (3) clean iff diff(3) <= M.")
    (println (format "Need:  diff(3)=%+.3f  <  M  <  diff(1)=%+.3f" w-diff r-diff))
    (if (> r-diff w-diff)
      (println (format "SEPARABLE: any margin in (%.3f, %.3f) fires (1), spares (3)." w-diff r-diff))
      (println (format "NOT SEPARABLE: diff(1)=%+.3f <= diff(3)=%+.3f — firing (1) necessarily fires the zero-FP case (3). => ESCALATE to (c) LLM domain-fit pass." r-diff w-diff)))
    (System/exit 0)))
