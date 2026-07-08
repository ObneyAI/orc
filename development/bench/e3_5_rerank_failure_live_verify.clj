(ns e3-5-rerank-failure-live-verify
  "THROWAWAY live-verify harness for slice E3.5 — proves the REAL rerank
   fallback path surfaces in R-Inject.

   Discipline: NO mock data. Real grain + real ColBERT bridge + real index
   (built by runner/start!). The ONLY injected fault is at the
   `reranker/rerank!` boundary — that IS the failure under test. ColBERT
   search + the index stay REAL. We assert by reading the real result maps
   + the real rendered R-Inject prepend back.

   Run (venv read-only from orc-main; -J-D BEFORE -M:dev):
     OPENROUTER_API_KEY=... \\
     clojure -J-Dcolbert.venv.path=/Users/darylroberts/Desktop/Code/orc-main/.venv-colbert \\
             -M:dev:test -e \"(require 'e3-5-rerank-failure-live-verify)(e3-5-rerank-failure-live-verify/run!)\"

   What it does:
     1. start! → real ontology-descriptions ColBERT index.
     2. BASELINE: real search-descriptions w/ reranker SUCCEEDING →
        expect :rerank-source :reranker.
     3. INDUCED FAILURE (throw, then nil) at reranker/rerank! ONLY:
        expect REAL search-descriptions stamps :colbert-fallback +
        :fitness-score nil on every result; classify-task /
        classify-behaviors carry :rerank-fallback? true.
     4. R-INJECT SURFACING: feed the fallback classifier payload through
        the REAL apply-r05-classifier-context and print the rendered
        prepend's caution lines verbatim."
  (:require [runner]
            [ai.obney.orc.ontology.interface :as ontology]
            [ai.obney.orc.ontology.core.reranker :as reranker]
            [ai.obney.orc.ontology.core.task-classifier :as tc]
            [ai.obney.orc.orc-service.core.todo-processors :as otp]
            [clojure.string :as str]))

(defn- system-ctx [] @@(resolve 'runner/system-state))

(defn- summarize [label results]
  (println (format "  [%s] count=%d sources=%s fitness=%s"
                   label
                   (count results)
                   (pr-str (mapv :rerank-source results))
                   (pr-str (mapv :fitness-score results)))))

(def ^:private probe-query
  "Classify a software-engineering task: analyze a contract for legal risks and obligations.")
(def ^:private probe-intent
  "Find the structural pattern that best fits this task.")

(defn run! []
  (println "\n========== E3.5 LIVE rerank-failure surfacing ==========")
  (println "Starting REAL system (builds real ColBERT ontology-descriptions index)...")
  (runner/start!)
  (let [ctx (system-ctx)]
    (try
      ;; ---------------------------------------------------------------
      ;; 1. BASELINE — real search, reranker SUCCEEDS → :reranker
      ;; ---------------------------------------------------------------
      (println "\n--- 1. BASELINE (real reranker, real ColBERT) ---")
      (let [baseline (ontology/search-descriptions ctx
                       {:query probe-query
                        :rerank-with-intent probe-intent
                        :k 3})]
        (summarize "baseline" baseline)
        (println "  baseline all :reranker? ="
                 (and (seq baseline)
                      (every? #(= :reranker (:rerank-source %)) baseline))))

      ;; ---------------------------------------------------------------
      ;; 2. INDUCED FAILURE at reranker/rerank! ONLY (search+index REAL)
      ;; ---------------------------------------------------------------
      (println "\n--- 2a. INDUCED reranker THROW (real ColBERT search underneath) ---")
      (let [thrown (with-redefs [reranker/rerank!
                                 (fn [& _] (throw (ex-info "induced reranker failure" {})))]
                     (ontology/search-descriptions ctx
                       {:query probe-query
                        :rerank-with-intent probe-intent
                        :k 3}))]
        (summarize "throw" thrown)
        (println "  throw all :colbert-fallback? ="
                 (and (seq thrown)
                      (every? #(= :colbert-fallback (:rerank-source %)) thrown)))
        (println "  throw all :fitness-score nil? ="
                 (and (seq thrown) (every? #(nil? (:fitness-score %)) thrown))))

      (println "\n--- 2b. INDUCED reranker NIL (real ColBERT search underneath) ---")
      (let [niled (with-redefs [reranker/rerank! (fn [& _] nil)]
                    (ontology/search-descriptions ctx
                      {:query probe-query
                       :rerank-with-intent probe-intent
                       :k 3}))]
        (summarize "nil" niled)
        (println "  nil all :colbert-fallback? ="
                 (and (seq niled)
                      (every? #(= :colbert-fallback (:rerank-source %)) niled)))
        (println "  nil all :fitness-score nil? ="
                 (and (seq niled) (every? #(nil? (:fitness-score %)) niled))))

      ;; ---------------------------------------------------------------
      ;; 3. classify-task / classify-behaviors carry :rerank-fallback?
      ;;    (induced fault still ONLY at reranker/rerank!)
      ;; ---------------------------------------------------------------
      (println "\n--- 3. classify-task / classify-behaviors :rerank-fallback? under induced throw ---")
      (let [{:keys [structural behavioral]}
            (with-redefs [reranker/rerank!
                          (fn [& _] (throw (ex-info "induced reranker failure" {})))]
              (let [s (ontology/classify-task ctx
                        {:task-signature probe-query :threshold 0.7 :walk-down? false})
                    b (ontology/classify-behaviors ctx
                        {:task-signature probe-query :threshold 0.6 :top-n 5})]
                {:structural s :behavioral b}))]
        (println "  classify-task :rerank-fallback? =" (:rerank-fallback? structural))
        (println "  classify-task top-candidate :rerank-source ="
                 (-> structural :top-candidates first :rerank-source))
        (println "  classify-behaviors :rerank-fallback? =" (:rerank-fallback? behavioral))
        (println "  classify-behaviors top behavior :rerank-source ="
                 (-> behavioral :behaviors first :rerank-source))

        ;; -------------------------------------------------------------
        ;; 4. R-INJECT SURFACING — feed fallback payload through the REAL
        ;;    apply-r05-classifier-context, read the rendered prepend back.
        ;; -------------------------------------------------------------
        (println "\n--- 4. R-Inject SURFACING (real apply-r05-classifier-context) ---")
        (let [;; Build the node :context exactly as the C-2c-2 wedge would
              ;; from these REAL fallback classify results.
              node {:id (random-uuid)
                    :name "rerank-fallback-probe"
                    :instruction "ORIGINAL INSTRUCTION BODY (sentinel)."
                    :context
                    {:tree-id (:assigned-tree-id structural)
                     :r05-classifier
                     {:structural {:assigned-tree-id (:assigned-tree-id structural)
                                   :confidence (:confidence structural)
                                   :was-fresh-mint? (:was-fresh-mint? structural)
                                   :reasoning (:reasoning structural)
                                   :top-candidates (vec (:top-candidates structural))
                                   :rerank-fallback? (boolean (:rerank-fallback? structural))}
                      :behavioral {:behaviors (vec (:behaviors behavioral))
                                   :rerank-fallback? (boolean (:rerank-fallback? behavioral))}}}}
              ;; apply-r05-classifier-context is private — resolve it.
              apply-r05 (requiring-resolve
                          'ai.obney.orc.orc-service.core.todo-processors/apply-r05-classifier-context)
              rendered (apply-r05 node (assoc ctx :sheet-id (random-uuid)))
              prepend (:instruction rendered)
              caution-lines (->> (str/split-lines prepend)
                                 (filter #(re-find #"(?i)fell back|treat .* caution|caution" %)))]
          (println "  rendered prepend chars:" (count prepend))
          (println "\n  >>> VERBATIM CAUTION LINES FROM RENDERED PREPEND <<<")
          (doseq [l caution-lines]
            (println "  | " l))
          (println "  >>> END CAUTION LINES <<<")
          (println "\n  caution present? =" (boolean (seq caution-lines)))))
      (finally
        (println "\nStopping system...")
        (runner/stop!))))
  (println "========== DONE ==========")
  :done)
