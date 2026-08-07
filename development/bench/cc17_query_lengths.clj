(ns cc17-query-lengths
  "CC-17 STEP 1 (MANDATORY, BEFORE any constant is chosen): measure the REAL
   production ColBERT query-token distribution.

   Two real populations, both reconstructed with the REAL production
   signature builders and tokenized with the REAL encoder
   (answerai-colbert-small-v1, DJL HuggingFace tokenizer):

     A. CONSOLIDATOR parent/behavioral inference queries — built by
        `consolidator/build-parent-inference-signature` from every living
        description body in the production dump (2,713 events,
        2026-07-30 -> 2026-08-03). Exact: the body IS in the dump, and the
        builder is the production fn.

     B. CLASSIFIER task signatures — built by
        `task-classifier/build-task-signature` from the REAL production
        repl-researcher node definitions that emitted the 156
        :ontology/task-classified events (orc-sessions
        implement-with-command-line). The signature is STATIC per node
        (instruction + reads + writes + tools), so the 156 events share it.

   Reports percentiles, not just the max. Prints its N."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [time-literals.read-write]
            [ai.obney.orc.colbert.core.encoder :as encoder]
            [ai.obney.orc.colbert.core.model-store :as model-store]
            [ai.obney.orc.ontology.core.consolidator]
            [ai.obney.orc.ontology.core.task-classifier :as tc]))

(def corpus-path
  "The P-E production dump (2,713 events, 2026-07-30 -> 2026-08-03). Override
   with -Dcc17.corpus when the scratchpad copy is gone; re-dump with
   scratchpad/pe/dump_corpus.clj."
  (or (System/getProperty "cc17.corpus")
      "/private/tmp/claude-501/-Users-darylroberts-Desktop-Code-orc-sessions/1dcb8677-d588-4d07-b419-779f8489bfdb/scratchpad/pe/corpus-a89f9f58-9761-42c9-bc67-94acba7bd4f2.edn"))

(def orc-sessions-src
  "/Users/darylroberts/Desktop/Code/orc-sessions/src/cjbarre/orc_sessions.clj")

(defn- read-def-value
  "Read the (def <sym> ...) form from `path` and eval its value expression.
   Used to lift the REAL production instruction string out of orc-sessions
   source without loading that project (READ-ONLY: the file is never written)."
  [path sym]
  (with-open [r (java.io.PushbackReader. (io/reader path))]
    (loop []
      (let [form (try (read {:eof ::eof :read-cond :allow} r)
                      (catch Exception _ ::skip))]
        (cond
          (= form ::eof) (throw (ex-info "def not found" {:sym sym}))
          (and (seq? form) (= 'def (first form)) (= sym (second form)))
          (eval (last form))
          :else (recur))))))

(defn percentiles
  "Nearest-rank percentiles of a numeric collection."
  [xs ps]
  (let [v (vec (sort xs))
        n (count v)]
    (into {} (map (fn [p]
                    [p (nth v (min (dec n)
                                   (max 0 (int (Math/ceil (- (* (/ p 100.0) n) 1))))))]))
          ps)))

(defn distribution
  [label token-counts budget]
  (let [n (count token-counts)
        over (filter #(> % budget) token-counts)
        pcts (percentiles token-counts [50 75 90 95 99 100])]
    {:label label
     :n n
     :budget budget
     :min (apply min token-counts)
     :mean (double (/ (reduce + token-counts) n))
     :p50 (pcts 50) :p75 (pcts 75) :p90 (pcts 90)
     :p95 (pcts 95) :p99 (pcts 99) :max (pcts 100)
     :truncated-n (count over)
     :truncated-pct (double (* 100.0 (/ (count over) n)))
     :max-tokens-discarded (if (seq over) (- (apply max over) budget) 0)
     :median-tokens-discarded (if (seq over)
                                (- ((percentiles over [50]) 50) budget)
                                0)}))

(defn print-dist [d]
  (println (format "  %-46s N=%-5d budget=%d" (:label d) (:n d) (:budget d)))
  (println (format "    min %d | p50 %d | p75 %d | p90 %d | p95 %d | p99 %d | max %d | mean %.1f"
                   (:min d) (:p50 d) (:p75 d) (:p90 d) (:p95 d) (:p99 d) (:max d) (:mean d)))
  (println (format "    OVER BUDGET: %d/%d (%.1f%%) | median discard %d | max discard %d"
                   (:truncated-n d) (:n d) (:truncated-pct d)
                   (:median-tokens-discarded d) (:max-tokens-discarded d))))

(defn -main [& _]
  (time-literals.read-write/print-time-literals-clj!)
  (let [enc (encoder/get-encoder (model-store/resolve-model-dir))
        query-maxlen (get-in enc [:consts :query-maxlen])
        budget (- query-maxlen 3)
        tok (fn [s] (count (encoder/encode-ids enc s)))
        evs (edn/read-string {:readers time-literals.read-write/tags}
                             (slurp corpus-path))
        _ (println "CORPUS EVENTS:" (count evs))
        _ (println "ENCODER query_maxlen:" query-maxlen "=> word-piece budget:" budget)

        ;; ---- Population A: consolidator inference signatures ----------------
        build-parent-sig @#'ai.obney.orc.ontology.core.consolidator/build-parent-inference-signature
        desc-types #{:ontology/tree-description-updated
                     :ontology/node-instance-description-updated
                     :ontology/node-type-description-updated}
        bodies (->> evs (filter #(desc-types (:event/type %))) (keep :body))
        _ (println "description bodies in dump:" (count bodies))
        a-queries (mapv build-parent-sig bodies)
        a-counts (mapv tok a-queries)

        ;; ---- Population B: classifier task signatures ------------------------
        implement-instruction (read-def-value orc-sessions-src 'implementation-mode-instruction)
        implementation-tools (read-def-value orc-sessions-src 'implementation-tools)
        implement-node {:instruction implement-instruction
                        :reads [:session :turns :active-plan :workspace-root
                                :user-message :tool-context]
                        :writes [:assistant-response]
                        :mcp-tools implementation-tools}
        b-sig (tc/build-task-signature implement-node)
        b-count (tok b-sig)
        n-classified (count (filter #(= :ontology/task-classified (:event/type %)) evs))]

    (println)
    (println "=== POPULATION A — consolidator inference queries (exact, from dump) ===")
    (print-dist (distribution "A. build-parent-inference-signature" a-counts budget))

    (println)
    (println "=== POPULATION B — classifier task signature (real production node) ===")
    (println (format "  implement-with-command-line signature: %d word-piece tokens" b-count))
    (println (format "  :ontology/task-classified events in dump: %d (all share this STATIC signature)"
                     n-classified))
    (println (format "  over budget by %d tokens; the encoder sees only the first %d"
                     (- b-count budget) budget))
    (println "  PREFIX the encoder actually sees:")
    (println (str "    " (pr-str (subs b-sig 0 (min 220 (count b-sig))))))

    (println)
    (println "=== COMBINED, event-weighted (each classification/consolidation = 1 query) ===")
    (print-dist (distribution "A+B event-weighted"
                              (into a-counts (repeat n-classified b-count))
                              budget))

    (println)
    (println "=== WHAT BUDGET WOULD BE NEEDED (population A, the varying one) ===")
    (doseq [ml [32 48 64 96 128 160 192 256 320 384 512]]
      (let [b (- ml 3)
            over (count (filter #(> % b) a-counts))]
        (println (format "  query_maxlen %3d (budget %3d): A truncated %3d/%d (%.1f%%) | B truncated? %s"
                         ml b over (count a-counts)
                         (double (* 100.0 (/ over (count a-counts))))
                         (if (> b-count b) "YES" "no")))))
    (println)
    (println "RAW A COUNTS:" (pr-str (vec (sort a-counts))))
    (System/exit 0)))
