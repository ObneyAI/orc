(ns ai.obney.orc.orc-service.rr25-documentation-truth-test
  "RR-25 — contract tests for the documentation truth pass: the false statements the
   issue names are gone from live code comments, docstrings and docs (historical
   records under docs/build-timeline, docs/issues, docs/prd and docs/adr, and the
   test-obligation checklist, are exempt — they record what was believed or what is
   forbidden, not the mechanism), the true statements are present where they belong,
   and ADR 0004 is referenced where the provider-deduplication claim used to live.

   Contract tests are never weakened to pass: a phrase is removed by rewriting the
   sentence to the landed behaviour, not by deleting the sentence."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def ^:private repo-root
  (let [cwd (System/getProperty "user.dir")]
    (loop [d (io/file cwd)]
      (cond (nil? d) (io/file cwd)
            (.exists (io/file d "workspace.edn")) d
            :else (recur (.getParentFile d))))))

(defn- live-files
  "Every .clj under components/*/src plus every top-level docs/*.md (historical
   records excluded)."
  []
  (concat (->> (file-seq (io/file repo-root "components"))
               (filter #(and (.isFile %) (str/ends-with? (.getName %) ".clj")
                             (str/includes? (.getPath %) "/src/"))))
          (->> (.listFiles (io/file repo-root "docs"))
               (filter #(and (.isFile %) (str/ends-with? (.getName %) ".md")
                             ;; the test-obligation ledger quotes the false phrasings it
                             ;; forbids; it records obligations, not the mechanism
                             (not= "DETERMINISTIC-E2E-TEST-CHECKLIST.md" (.getName %)))))))

(defn- hits [pattern]
  (for [f (live-files)
        [i line] (map-indexed vector (str/split-lines (slurp f)))
        :when (re-find pattern line)]
    (str (.getPath f) ":" (inc i) ": " (str/trim line))))

(deftest the-idempotency-key-is-never-described-as-provider-deduplication
  (testing "no live comment or doc says a provider deduplicates on our idempotency key"
    (let [h (hits #"(?i)(provider|vendor|openai|anthropic|openrouter)[^\n]{0,80}(dedup|idempotency[- ]key)|(dedup|idempotency[- ]key)[^\n]{0,80}(by|at|on) the (provider|vendor)")]
      (is (empty? h) (str/join "\n" h))))
  (testing "the key's real purpose is stated where it is used, with ADR 0004 referenced"
    (let [executor (slurp (io/file repo-root "components/orc-service/src/ai/obney/orc/orc_service/core/executor.clj"))]
      (is (re-find #"(?i)ADR 0004" executor) "the provider-call sites reference ADR 0004")
      (is (re-find #"(?i)(recognis|recogniz)e[sd]? an already[- ]dispatched|already[- ]dispatched call" executor)
          "the executor states the key recognises an already-dispatched call on resume"))))

(deftest harvest-is-documented-as-shipped
  (let [sil (slurp (io/file repo-root "docs/SELF-IMPROVING-LOOP.md"))]
    (is (not (re-find #"(?i)not yet shipped|unshipped|not shipped on this branch" sil)))
    (is (re-find #"(?i)harvest[^\n]{0,200}(is live|is shipped|ships|runs today|is wired)" sil)
        "harvest is described as live")
    (is (re-find #"(?i)verdict occurrence|counted at outcome" sil)
        "recurrence is described as counted at the campaign's verdict, not at classification")))

(deftest the-tree-generated-event-cadence-matches-its-actual-cadence
  (testing "code comments and docs say the event fires once per campaign carrying the last tree"
    (let [h (hits #"(?i)(emit|emits|emitted|fires?)[^\n]{0,40}tree-generated[^\n]{0,40}(when (a |the )?tree is generated|per (emit|tree|phase[- ]?1)|(for )?each (intermediate |emit|tree))|tree-generated[^\n]{0,60}(per emit|per tree|each emit|every emit|per phase[- ]?1)|per-phase-1-iteration[^\n]{0,40}tree")]
      (is (empty? h) (str/join "\n" h)))
    (let [tp (slurp (io/file repo-root "components/orc-service/src/ai/obney/orc/orc_service/core/todo_processors.clj"))]
      (is (re-find #"(?i)tree-generated[^\n]{0,120}once per campaign|once per campaign[^\n]{0,120}tree-generated" tp)))))

(deftest arc-drift-is-corrected-in-live-code-comments
  (testing "the tree-class counter is described as ticking on verdict occurrences, not classifications"
    (let [h (hits #"(?i)ticked on every :ontology/task-classified|bumps? [^\n]{0,40}on :ontology/task-classified|counted (at|on) classification")]
      (is (empty? h) (str/join "\n" h)))
    (let [tc (slurp (io/file repo-root "components/ontology/src/ai/obney/orc/ontology/core/task_classifier.clj"))]
      (is (re-find #"tree-class-occurrence-recorded" tc) "the classifier docstring names the verdict occurrence as the counter's source")))
  (testing "docs describe coherence over winning shapes, report-only, and patterns offered whole with bindings"
    (let [sil (slurp (io/file repo-root "docs/SELF-IMPROVING-LOOP.md"))
          rlm (slurp (io/file repo-root "docs/RLM-GUIDE.md"))]
      (is (re-find #"(?i)winning shape" sil))
      (is (re-find #"(?i)report[- ]only" sil))
      (is (re-find #"(?i)(offered|rendered|shown) whole|never truncated" rlm))
      (is (re-find #"(?i)(reads|writes)[^\n]{0,60}(binding|declare)" rlm))
      (is (not (re-find #"(?i)truncat[^\n]{0,40}(1,?200|worked example|recommended.pattern)" rlm)) "no doc still describes the removed pattern cap"))))
