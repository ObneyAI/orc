(ns el5-colbert-separability-probe
  "THROWAWAY (orchestrator /inspect-orc + EL-5 option-D gate): re-run the EL-5
   separability test using COLBERT late-interaction (token-level MaxSim) instead
   of all-MiniLM single-vector cosine. The all-MiniLM probe was NOT SEPARABLE
   (refactor diff −0.112 < web-search diff −0.018) because pooling blurs
   'extract a helper' vs 'identity rename'. ColBERT scores per-token, so the
   sharpened avoid-guard's literal 'extract a helper, pull out a function' tokens
   should MaxSim-match the refactor task directly. Does ColBERT make case (1)
   diff > case (3) diff (separable)?  If yes → option D (pluggable ColBERT
   scorer) is viable. If no → the distinction is beyond token-matching → (c)/B/C.

   colbert/rerank scores ALL docs against ONE query in a single call, so the
   avoid vs good scores for a given task are directly comparable (same scale).

   Run: clojure -J-Dcolbert.venv.path=<orc-main venv> -J-Dcolbert.bridge.script=<this worktree> -M:dev -m el5-colbert-separability-probe"
  (:require [runner]
            [ai.obney.orc.colbert.interface :as colbert]))

(def refactor-task
  "INSTRUCTION:\nRefactor the order service to extract a pure pricing helper from the request handler, preserving existing behavior and keeping all current tests green.\n\nREADS: :user-message :active-plan :workspace-root\nWRITES: :assistant-response\nMCP-TOOLS: shell/exec fs/read fs/list\nBROWSER-TOOLS: (none)")

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

(def websearch-task
  "INSTRUCTION:\nSearch the web for the latest documentation on the payment API and summarize what you find.\n\nREADS: :user-message\nWRITES: :assistant-response\nMCP-TOOLS: web/search\nBROWSER-TOOLS: (none)")
(def websearch-avoid
  ["the web search requires special elevated permissions or authenticated access the agent does not hold"
   "the task is purely computational with no external lookup need"])
(def websearch-good
  ["Web-search gathers fresh external information by issuing search queries and reading results to ground downstream work."
   "the task needs current external information not already in context"])

(defn score-task
  "One colbert/rerank call: query=task, documents=avoid++good. Returns max avoid
   score, max good score, and the contrast diff (same scale → comparable)."
  [ctx task avoid-docs good-docs]
  (let [avoid (vec (remove empty? avoid-docs))
        good (vec (remove empty? good-docs))
        res (colbert/rerank ctx {:query task :documents (vec (concat avoid good))})
        by-content (into {} (map (juxt :content :score)) res)
        amax (apply max (keep by-content avoid))
        gmax (apply max (keep by-content good))]
    {:avoid amax :good gmax :diff (- amax gmax)}))

(defn -main [& _]
  (runner/start!)
  (let [ctx (deref (var-get (requiring-resolve 'runner/system-state)))
        r (score-task ctx refactor-task rename-avoid rename-good)
        w (score-task ctx websearch-task websearch-avoid websearch-good)]
    (println "\n=== EL-5 COLBERT SEPARABILITY PROBE (late-interaction MaxSim) ===")
    (println (format "CASE (1) refactor force-fit [MUST FIRE]:  avoid=%.3f good=%.3f  diff=%+.3f" (:avoid r) (:good r) (:diff r)))
    (println (format "CASE (3) web-search own-domain [MUST=0]:  avoid=%.3f good=%.3f  diff=%+.3f" (:avoid w) (:good w) (:diff w)))
    (println "")
    (println (format "Need:  diff(3)=%+.3f  <  M  <  diff(1)=%+.3f" (:diff w) (:diff r)))
    (if (> (:diff r) (:diff w))
      (println (format "SEPARABLE with COLBERT: any margin in (%.3f, %.3f) fires (1), spares (3). => option D VIABLE." (:diff w) (:diff r)))
      (println (format "STILL NOT SEPARABLE with COLBERT: diff(1)=%+.3f <= diff(3)=%+.3f. => token-matching insufficient; fall back to (c)/B/C." (:diff r) (:diff w))))
    (runner/stop!)
    (System/exit 0)))
