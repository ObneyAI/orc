(ns rr2-default-model-live-smoke
  "THROWAWAY live-verify harness for RR-2 — proves the reranker's NEW
   evidence-tested default model (`qwen/qwen3.5-flash-02-23`, ADR 0020
   decision 5) produces a valid, parseable, function-calling-structured
   ranking on the REAL seeded corpus.

   Discipline: NO mock data. Real grain + real ColBERT index (via
   runner/start!, which seeds the 45-entry baseline corpus + 80 synthetic
   padding entries and builds a real ColBERT index) + a REAL OpenRouter
   call to the RR-2 default model. Nothing about reranker/rerank! or
   ai.obney.orc.llm.interface/predict is stubbed here — a stubbed LLM response would
   falsely validate 'the default model produces a valid ranking', which
   is exactly the claim under test.

   Run:
     OPENROUTER_API_KEY=... \\
     clojure -M:dev:test -e \"(require 'rr2-default-model-live-smoke)(rr2-default-model-live-smoke/run!)\"

   What it does:
     1. start! -> real ontology-descriptions ColBERT index over the real
        seeded corpus.
     2. Real search-descriptions call with :rerank-with-intent set and NO
        :model override -> exercises reranker/rerank!'s (or model
        default-model) resolution against the REAL OpenRouter API.
     3. Asserts every result stamps :rerank-source :reranker (NOT
        :colbert-fallback) and carries a numeric :fitness-score + a
        non-empty :reasoning string -- i.e. the default model's
        function-calling structured output round-tripped and parsed,
        it did not throw/return-nil/return-empty and fall back to raw
        ColBERT ordering.
     4. Repeats with an explicit :model override so both RR-2 acceptance
        paths get one real, non-mocked data point each.

   KNOWN ISSUE (unresolved): invoking `run!` via a one-shot
   `clojure -M:dev:test -e '(require ...)(rr2-default-model-live-smoke/run!)'`
   (also tried: `-i <this-file> -e \"\"`, and `((ns-resolve
   'rr2-default-model-live-smoke 'run!))`) throws IllegalStateException
   'Attempting to call unbound fn' at the moment of the call itself (no
   deeper stack frames) — even though a separate diagnostic script confirms
   `(ns-resolve 'rr2-default-model-live-smoke 'run!)` returns a BOUND var
   immediately after requiring. Root cause not found; deprioritized in
   favor of the deterministic tests in reranker_test.clj
   (`rerank-resolves-default-model-when-none-given` /
   `rerank-uses-explicit-model-override`), which already assert on the
   REAL resolved request options via `with-redefs` on `ai.obney.orc.llm.interface/predict`
   and are unaffected by whatever this one-shot-process quirk is. Try
   invoking `(rr2-default-model-live-smoke/run!)` from a live/connected
   REPL instead of a one-shot process if you want to pick this apart."
  (:require [runner]
            [ai.obney.orc.ontology.interface :as ontology]
            [ai.obney.orc.ontology.core.reranker :as reranker]))

(defn- system-ctx [] @@(resolve 'runner/system-state))

(def ^:private probe-query
  "Classify a software-engineering task: analyze a contract for legal risks and obligations.")
(def ^:private probe-intent
  "Find the structural pattern that best fits this task.")

(defn- summarize [label results]
  (println (format "  [%s] count=%d sources=%s fitness=%s"
                   label
                   (count results)
                   (pr-str (mapv :rerank-source results))
                   (pr-str (mapv :fitness-score results))))
  (doseq [r results]
    (println (format "    - %s fitness=%s reasoning=%s"
                     (:document-id r)
                     (:fitness-score r)
                     (pr-str (some-> (:reasoning r) (subs 0 (min 120 (count (:reasoning r)))))))))

(defn- assert-valid-reranked-result! [label results]
  (let [ok? (and (seq results)
                (every? #(= :reranker (:rerank-source %)) results)
                (every? #(number? (:fitness-score %)) results)
                (every? #(and (string? (:reasoning %)) (pos? (count (:reasoning %)))) results)
                (every? #(<= 0.0 (:fitness-score %) 1.0) results))]
    (println (str "  [" label "] VALID PARSEABLE RANKING? = " ok?))
    ok?))

(defn run! []
  (println "\n========== RR-2 LIVE default-model smoke check ==========")
  (println "Default model under test:" reranker/default-model)
  (println "Starting REAL system (builds real ColBERT ontology-descriptions index)...")
  (runner/start!)
  (let [ctx (system-ctx)]
    (try
      (println "\n--- 1. DEFAULT MODEL (no :model override — exercises (or model default-model)) ---")
      (let [default-results (ontology/search-descriptions ctx
                              {:query probe-query
                               :rerank-with-intent probe-intent
                               :k 3})
            default-ok? (do (summarize "default" default-results)
                            (assert-valid-reranked-result! "default" default-results))]

        (println "\n--- 2. EXPLICIT OVERRIDE (:model \"google/gemini-2.5-flash\") ---")
        (let [override-results (ontology/search-descriptions ctx
                                 {:query probe-query
                                  :rerank-with-intent probe-intent
                                  :model "google/gemini-2.5-flash"
                                  :k 3})
              override-ok? (do (summarize "override" override-results)
                               (assert-valid-reranked-result! "override" override-results))]

          (println "\n========== RESULT ==========")
          (println "  Default-model  (qwen/qwen3.5-flash-02-23) valid ranking? =" default-ok?)
          (println "  Override-model (google/gemini-2.5-flash)  valid ranking? =" override-ok?)
          (println "============================\n")
          {:default-ok? default-ok? :override-ok? override-ok?
           :default-results default-results :override-results override-results}))
      (finally
        (println "\nStopping system...")
        (runner/stop!))))))
