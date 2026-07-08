(ns el5-zero-fp-check
  "THROWAWAY — EL-5 four-case (3): web-search-on-own-domain => penalty 0 (zero
   false positive). Runs the REAL :colbert scorer (production default config)
   through the SAME score-candidate path apply-rerank uses, on a synthetic
   web-search candidate + a web-search task. The candidate's :good-when (the
   use-case) must beat its :avoid-when ('…needs special permissions') on a plain
   web-search task => contrast < margin => penalty 0. ALSO runs the refactor
   force-fit candidate as a positive control (penalty MUST fire) so we prove the
   scorer is alive (rule out the harness: a dead scorer would give 0 for both).

   Real ColBERT + production knobs. Run:
     OPENROUTER_API_KEY=… clojure -J-Dcolbert.venv.path=/Users/darylroberts/Desktop/Code/orc-main/.venv-colbert -M:dev -m el5-zero-fp-check"
  (:require [runner]
            [ai.obney.orc.ontology.core.domain-penalty :as dp]))

;; The web-search behavior (synthetic, from the separability probe). Its positive
;; use-case is a plain web search; its avoid-condition is the 'special
;; permissions' qualifier — which a plain web-search task does NOT match.
(def websearch-candidate
  {:document-id "websearch"
   :avoid-when ["the web search requires special elevated permissions or authenticated access the agent does not hold"
                "the task is purely computational with no external lookup need"]
   :content "Web-search gathers fresh external information by issuing search queries and reading results to ground downstream work."
   :strengths [{:good-when "the task needs current external information not already in context"}]})

(def websearch-task
  "INSTRUCTION:\nSearch the web for the latest documentation on the payment API and summarize what you find.\n\nREADS: :user-message\nWRITES: :assistant-response\nMCP-TOOLS: web/search\nBROWSER-TOOLS: (none)")

;; Positive control: the rename-move-symbol force-fit candidate on a refactor
;; task — the penalty MUST fire here (proves the scorer is live + discriminating).
(def rename-candidate
  {:document-id "rename"
   :avoid-when ["the task is to extract a helper, pull out a function, or otherwise refactor/restructure code — that is code-building/refactor, NOT a pure identity rename or move of an existing symbol"
                "the task changes behavior or adds functionality — that is code-building, not a behavior-preserving rename/move"]
   :content "Rename-move-symbol is a behavior-preserving, EXHAUSTIVE cross-file identity refactor — rename a function everywhere, move a symbol to another namespace — changing NOTHING else."
   :strengths [{:good-when "a pure rename/move with behavior preserved"}]})

(def refactor-task
  "INSTRUCTION:\nRefactor the order service to extract a pure pricing helper from the request handler, preserving existing behavior and keeping all current tests green.\n\nREADS: :user-message :active-plan :workspace-root\nWRITES: :assistant-response\nMCP-TOOLS: shell/exec fs/read fs/list\nBROWSER-TOOLS: (none)")

(defn -main [& _]
  (runner/start!)
  (let [ctx (deref (var-get (requiring-resolve 'runner/system-state)))
        cfg (assoc dp/default-penalty-config :scorer :colbert)
        scorer (dp/make-scorer ctx cfg)
        ws (dp/score-candidate websearch-candidate websearch-task scorer cfg)
        rn (dp/score-candidate rename-candidate refactor-task scorer cfg)]
    (println "\n=== EL-5 CASE (3) ZERO-FALSE-POSITIVE CHECK (real :colbert, prod knobs) ===")
    (println "config:" (select-keys cfg [:scorer :penalty-scale :margin :penalty-cap]))
    (println (format "CASE (3) web-search-on-own-domain: cos-avoid=%.4f cos-good=%.4f diff=%+.4f penalty=%.3f"
                     (:cos-avoid ws) (:cos-good ws) (- (:cos-avoid ws) (:cos-good ws)) (:penalty ws)))
    (println (format "CONTROL  refactor force-fit (rename):  cos-avoid=%.4f cos-good=%.4f diff=%+.4f penalty=%.3f"
                     (:cos-avoid rn) (:cos-good rn) (- (:cos-avoid rn) (:cos-good rn)) (:penalty rn)))
    (println "")
    (println "CASE (3) PASS (penalty 0)? =" (zero? (:penalty ws)))
    (println "CONTROL fires (penalty > 0, scorer alive)? =" (pos? (:penalty rn)))
    (println (if (and (zero? (:penalty ws)) (pos? (:penalty rn)))
               "RESULT: zero-FP holds AND the scorer discriminates — case (3) PASS."
               "RESULT: FAIL — inspect cos/penalty (a dead scorer gives 0 for both; a too-low margin fires case 3)."))
    (runner/stop!)
    (System/exit 0)))
