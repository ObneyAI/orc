(ns rs7-traffic-sweep-test
  "RS-7 traffic-tooling bundle — SYNTHETIC suite (no LLM, no ColBERT index).
   Loaded directly via `load-file` (development/test is not on the :dev:test
   classpath — see deps.edn — so this stays a plain load-file target rather
   than pulling development/test onto the shared classpath the other RS-7
   slice concurrently editing components/*/test does not touch).

   Exercises the PURE / file-IO-only surface of `traffic-corpus-gen` and
   `rs7-traffic-sweep`: slug parsing, interleave order, resume decision,
   near-dup flags (with a FAKE colbert-neighbour capability — the injected-
   capability seam pattern), freeze! sha stability, `analyse`, and
   `compare-runs`. The ONE live behavior (`run-pass!` against the real
   wedge, `install-tee!`'s :usage/:rerank-calls capture, kill-and-restore)
   is proved separately by the mandatory live smoke — see the HANDOFF."
  (:require [clojure.test :refer [deftest testing is run-tests]]
            [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.pprint :as pp]
            [clojure.string :as str]
            [traffic-corpus-gen :as tcg]
            [rs7-traffic-sweep :as rs7]))

;; =============================================================================
;; Test fixture helpers — a throwaway corpus dir under the system temp dir
;; =============================================================================

(defn- temp-dir! [prefix]
  (.toFile (java.nio.file.Files/createTempDirectory prefix (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- write-task! [corpus-dir slug text]
  (let [tasks-dir (io/file corpus-dir "tasks")]
    (.mkdirs tasks-dir)
    (spit (io/file tasks-dir (str slug ".txt")) text)))

(defn- write-manifest! [corpus-dir manifest]
  (spit (io/file corpus-dir "manifest.edn") (with-out-str (pp/pprint manifest))))

;; =============================================================================
;; Three fixture briefs (marked :fixture? true) — used only to exercise
;; interleave-order's grouping logic, never sent to a real LLM.
;; =============================================================================

(def fixture-briefs
  [{:brief-id "fx-marathon" :group "marathon-training" :in-domain? false
    :subject-matter "training-plan adjustment" :material "a runner's current weekly mileage"
    :output-kind "a revised weekly schedule" :instance-space "the target race distance"
    :fixture? true}
   {:brief-id "fx-recipe" :group "recipe-scaling" :in-domain? false
    :subject-matter "recipe scaling" :material "an ingredient list"
    :output-kind "a scaled ingredient list" :instance-space "the target serving count"
    :fixture? true}
   {:brief-id "fx-legal" :group "legal-issue-detection" :in-domain? true
    :expected-seed-id :legal-issue-detection
    :subject-matter "contract issue-spotting" :material "a contract excerpt"
    :output-kind "a bulleted risk summary" :instance-space "the contract type"
    :fixture? true}])

;; =============================================================================
;; traffic-corpus-gen: slug parsing
;; =============================================================================

(deftest parse-slug-test
  (testing "a well-formed slug parses to index/group/variant"
    (is (= {:index 7 :group "marathon-training" :variant 3}
           (tcg/parse-slug "007-marathon-training-v03"))))
  (testing "round-trips through slug"
    (is (= "007-marathon-training-v03" (tcg/slug 7 "marathon-training" 3))))
  (testing "a malformed slug parses to nil"
    (is (nil? (tcg/parse-slug "not-a-slug")))
    (is (nil? (tcg/parse-slug "07-marathon-v3")))))

;; =============================================================================
;; traffic-corpus-gen: interleave order
;; =============================================================================

(deftest interleave-order-test
  (testing "round-robins by VARIANT across groups before advancing variants"
    (let [order (tcg/interleave-order fixture-briefs 2 42)]
      (is (= 6 (count order)))
      (is (= [1 2 3 4 5 6] (mapv :index order)))
      ;; all three groups appear at variant 1 before ANY appears at variant 2
      (is (= [1 1 1 2 2 2] (mapv :variant order)))
      (is (= #{"marathon-training" "recipe-scaling" "legal-issue-detection"}
             (set (map :group (take 3 order)))))
      (is (= (set (map :group (take 3 order))) (set (map :group (drop 3 order)))))))
  (testing "deterministic for a fixed seed"
    (is (= (tcg/interleave-order fixture-briefs 2 42)
           (tcg/interleave-order fixture-briefs 2 42))))
  (testing "every (brief, variant) pair appears exactly once"
    (let [order (tcg/interleave-order fixture-briefs 3 7)
          pairs (map (juxt :brief-id :variant) order)]
      (is (= 9 (count order)))
      (is (= (count pairs) (count (distinct pairs)))))))

;; =============================================================================
;; rs7-traffic-sweep: resume decision
;; =============================================================================

(deftest resume-decision-test
  (testing "no prior record — never skip"
    (is (false? (rs7/resume-decision nil false)))
    (is (false? (rs7/resume-decision nil true))))
  (testing ":ok is always resumed (skipped)"
    (is (true? (rs7/resume-decision {:status :ok} false)))
    (is (true? (rs7/resume-decision {:status :ok} true))))
  (testing ":error is skipped unless retry-errors?"
    (is (true? (rs7/resume-decision {:status :error} false)))
    (is (false? (rs7/resume-decision {:status :error} true)))))

;; =============================================================================
;; traffic-corpus-gen: flag-near-dups! — FAKE colbert-neighbour capability
;; =============================================================================

(deftest flag-near-dups-test
  (let [corpus-dir (temp-dir! "rs7-flagdups")
        marathon-v1 "Please help me scale my eight week marathon training plan for a fall race next month I run four times weekly"
        marathon-v2 "Please help me scale my eight week marathon training plan for a fall race next month I run five times weekly"
        recipe-v1 "Please help me scale my eight week dinner party recipe plan for a fall event next month I cook four times weekly"
        legal-v1 "Please review the attached contract and tell me what could go wrong for us"]
    (write-task! corpus-dir "001-marathon-training-v01" marathon-v1)
    (write-task! corpus-dir "002-marathon-training-v02" marathon-v2)
    (write-task! corpus-dir "003-recipe-scaling-v01" recipe-v1)
    (write-task! corpus-dir "004-legal-issue-detection-v01" legal-v1)
    (write-manifest! corpus-dir
      {:entries
       [{:slug "001-marathon-training-v01" :group "marathon-training" :variant 1
         :in-domain? false :review-status :accepted}
        {:slug "002-marathon-training-v02" :group "marathon-training" :variant 2
         :in-domain? false :review-status :accepted}
        {:slug "003-recipe-scaling-v01" :group "recipe-scaling" :variant 1
         :in-domain? false :review-status :accepted}
        {:slug "004-legal-issue-detection-v01" :group "legal-issue-detection" :variant 1
         :in-domain? true :expected-output-kind "a bulleted risk summary" :review-status :accepted}]})
    (let [manifest (edn/read-string (slurp (io/file corpus-dir "manifest.edn")))
          fake-neighbour-fn (fn [text others]
                              (when (seq others)
                                {:from-other-group? (= text marathon-v2)}))
          flagged (tcg/flag-near-dups! corpus-dir manifest {:neighbour-fn fake-neighbour-fn})
          by-slug (into {} (map (juxt :slug identity)) (:entries flagged))]
      (testing "within-group near-repeat (Jaccard > 0.6) flags the LATER variant :repeat"
        (is (contains? (set (get-in by-slug ["002-marathon-training-v02" :flags])) :repeat))
        (is (not (contains? (set (get-in by-slug ["001-marathon-training-v01" :flags])) :repeat))))
      (testing "cross-group overlap (Jaccard > 0.35) flags :ambiguous-truth both ways"
        (is (contains? (set (get-in by-slug ["003-recipe-scaling-v01" :flags])) :ambiguous-truth)))
      (testing "the injected colbert-neighbour capability drives :neighbour-other-group"
        (is (contains? (set (get-in by-slug ["002-marathon-training-v02" :flags])) :neighbour-other-group))
        (is (not (contains? (set (get-in by-slug ["001-marathon-training-v01" :flags])) :neighbour-other-group))))
      (testing "an in-domain entry missing the brief's own output-kind tokens is flagged"
        (is (contains? (set (get-in by-slug ["004-legal-issue-detection-v01" :flags])) :output-kind-missing))))
    (io/file corpus-dir)))

;; =============================================================================
;; traffic-corpus-gen: freeze! sha stability
;; =============================================================================

(deftest freeze-sha-stability-test
  (let [corpus-dir (temp-dir! "rs7-freeze")]
    (write-task! corpus-dir "001-marathon-training-v01" "scale my plan for a marathon")
    (write-task! corpus-dir "002-recipe-scaling-v01" "scale my recipe for a party")
    (write-manifest! corpus-dir
      {:entries [{:slug "001-marathon-training-v01" :group "marathon-training" :review-status :accepted}
                 {:slug "002-recipe-scaling-v01" :group "recipe-scaling" :review-status :accepted}]})
    (let [frozen-1 (tcg/freeze! corpus-dir)
          frozen-2 (tcg/freeze! corpus-dir)]
      (testing "freezing twice over the same accepted text is byte-stable"
        (is (some? (:corpus-sha256 frozen-1)))
        (is (= (:corpus-sha256 frozen-1) (:corpus-sha256 frozen-2)))))
    (testing "changing an accepted entry's text changes the sha"
      (let [before (:corpus-sha256 (edn/read-string (slurp (io/file corpus-dir "manifest.edn"))))]
        (write-task! corpus-dir "001-marathon-training-v01" "scale my plan for a MUCH LONGER marathon race")
        (let [after (tcg/freeze! corpus-dir)]
          (is (not= before (:corpus-sha256 after))))))
    (testing "rejecting an entry excludes it from the sha"
      (let [manifest (edn/read-string (slurp (io/file corpus-dir "manifest.edn")))
            with-reject (update manifest :entries
                                (fn [es] (mapv #(if (= "002-recipe-scaling-v01" (:slug %))
                                                  (assoc % :review-status :rejected) %)
                                              es)))
            _ (write-manifest! corpus-dir with-reject)
            rejected-sha (:corpus-sha256 (tcg/freeze! corpus-dir))
            _ (write-manifest! corpus-dir manifest) ;; restore
            accepted-sha (:corpus-sha256 (tcg/freeze! corpus-dir))]
        (is (not= rejected-sha accepted-sha))))))

;; =============================================================================
;; rs7-traffic-sweep: analyse — hand-built records, no live system
;; =============================================================================

(def a-id (random-uuid))
(def seed-id (random-uuid))
(def c-id (random-uuid))

(def hand-built-records
  [{:slug "001-marathon-v01" :pass 1 :status :ok :variant 1
    :assigned-via :mint-domain-child :assigned-tree-id a-id :parent-tree-id (random-uuid)
    :domain-label "marathon-training" :corpus-group "marathon" :corpus-in-domain? false
    :reached-via :mint :elapsed-ms 1000 :usage {:total-tokens 500}
    :classified-event {:behavioral-subtrees [{:was-fresh-mint? true}]}}
   {:slug "002-marathon-v02" :pass 1 :status :ok :variant 2
    :assigned-via :match :assigned-tree-id a-id :corpus-group "marathon" :corpus-in-domain? false
    :reached-via :index-match :newborn-in-index? true :elapsed-ms 1200 :usage {:total-tokens 400}
    :classified-event {:behavioral-subtrees [{:was-fresh-mint? false :behavior-id :b1}]}}
   {:slug "003-marathon-v03" :pass 1 :status :ok :variant 3
    :assigned-via :match :assigned-tree-id a-id :corpus-group "marathon" :corpus-in-domain? false
    :reached-via :index-match :newborn-in-index? false :elapsed-ms 900 :usage {:total-tokens 300}}
   {:slug "004-legal-v01" :pass 1 :status :ok :variant 1
    :assigned-via :match :assigned-tree-id seed-id :corpus-group "legal-issue-detection"
    :corpus-in-domain? true :reached-via :seed-match :elapsed-ms 800 :usage {:total-tokens 200}}
   {:slug "005-confounder-v01" :pass 1 :status :ok :variant 1
    :assigned-via nil :corpus-group "confounder-x" :corpus-in-domain? false
    :confounder-of "legal-issue-detection" :domain-deferral {:reason :lookup-failed}
    :elapsed-ms 1500 :usage {:total-tokens 100}}
   {:slug "006-confounder-v02" :pass 1 :status :ok :variant 2
    :assigned-via :mint :assigned-tree-id c-id :corpus-group "confounder-x" :corpus-in-domain? false
    :confounder-of "legal-issue-detection" :reached-via :mint :elapsed-ms 1100 :usage {:total-tokens 600}}])

(def hand-built-manifest
  {:entries (mapv (fn [r] {:slug (:slug r) :group (:corpus-group r) :review-status :accepted})
                  hand-built-records)})

(deftest analyse-test
  (let [a (rs7/analyse hand-built-records hand-built-manifest)]
    (testing "totals + deferrals"
      (is (= 6 (:total a)))
      (is (= 1 (:deferral-count a)))
      (is (= {:lookup-failed 1} (:deferrals-by-reason a))))
    (testing "in-domain mints and confounder mints"
      (is (= 0 (:in-domain-mint-count a)))
      (is (= 1 (:confounder-mint-count a)))
      (is (= ["006-confounder-v02"] (:confounder-mint-slugs a))))
    (testing "per-group convergence — marathon converges on ONE assigned id"
      (let [marathon (get-in a [:groups "marathon"])]
        (is (= 1 (:child-count marathon)))
        (is (true? (:converged-strict? marathon)))
        (is (= 1 (:mint-count marathon)))))
    (testing "behavioral fresh-mint rate over the two behavioral entries"
      (is (= 0.5 (:behavioral-fresh-mint-rate a))))
    (testing "newborn-in-index rate over index-match/graph-land/walk-down candidates"
      (is (= 0.5 (:newborn-in-index-rate a))))
    (testing "manifest completeness — every accepted slug had a record"
      (is (= [] (:missing-slugs a))))
    (testing "manifest completeness catches a slug that never got a record"
      (let [manifest-with-gap (update hand-built-manifest :entries conj
                                      {:slug "999-never-ran-v01" :group "ghost" :review-status :accepted})
            a2 (rs7/analyse hand-built-records manifest-with-gap)]
        (is (= ["999-never-ran-v01"] (:missing-slugs a2)))))))

(deftest analyse-pass-to-pass-test
  (let [pass-2 (mapv #(update % :pass (constantly 2)) hand-built-records)
        a (rs7/analyse pass-2 hand-built-manifest {:pass-1-records hand-built-records})]
    (testing "identical pass-2 records are 100% identity-stable"
      (is (= 1.0 (:identity-stability a))))))

;; =============================================================================
;; rs7-traffic-sweep: compare-runs
;; =============================================================================

(defn- write-run-edn! [dir m]
  (.mkdirs (io/file dir))
  (spit (io/file dir "run.edn") (with-out-str (pp/pprint m))))

(defn- write-pass-record! [dir pass record]
  (let [d (io/file dir (str "pass-" pass))]
    (.mkdirs d)
    (spit (io/file d (str (:slug record) ".edn")) (with-out-str (pp/pprint record)))))

(deftest compare-runs-refuses-mismatched-corpus-test
  (let [dir-a (temp-dir! "rs7-cmp-a") dir-b (temp-dir! "rs7-cmp-b")]
    (write-run-edn! dir-a {:corpus-sha256 "sha-A" :task-order ["001" "002"]})
    (write-run-edn! dir-b {:corpus-sha256 "sha-B" :task-order ["001" "002"]})
    (let [result (rs7/compare-runs dir-a dir-b)]
      (is (true? (:refused? result)))
      (is (= :corpus-sha-mismatch (:reason result)))
      (is (not (.exists (io/file dir-b "COMPARISON.md")))))))

(deftest compare-runs-refuses-mismatched-order-test
  (let [dir-a (temp-dir! "rs7-cmp-a") dir-b (temp-dir! "rs7-cmp-b")]
    (write-run-edn! dir-a {:corpus-sha256 "sha-X" :task-order ["001" "002"]})
    (write-run-edn! dir-b {:corpus-sha256 "sha-X" :task-order ["002" "001"]})
    (let [result (rs7/compare-runs dir-a dir-b)]
      (is (true? (:refused? result)))
      (is (= :task-order-mismatch (:reason result))))))

(deftest compare-runs-reports-changed-slugs-test
  (let [dir-a (temp-dir! "rs7-cmp-a") dir-b (temp-dir! "rs7-cmp-b")]
    (write-run-edn! dir-a {:corpus-sha256 "sha-X" :task-order ["001" "002"]})
    (write-run-edn! dir-b {:corpus-sha256 "sha-X" :task-order ["001" "002"]})
    (write-pass-record! dir-a 1 {:slug "001" :assigned-via :match :domain-label "marathon-training"})
    (write-pass-record! dir-b 1 {:slug "001" :assigned-via :match :domain-label "marathon-training"})
    (write-pass-record! dir-a 1 {:slug "002" :assigned-via :mint-domain-child :domain-label "recipe-scaling"})
    (write-pass-record! dir-b 1 {:slug "002" :assigned-via :match :domain-label "recipe-scaling"})
    (let [result (rs7/compare-runs dir-a dir-b)]
      (is (false? (:refused? result)))
      (is (= ["002"] (:changed-slugs result)))
      (is (.exists (io/file dir-b "COMPARISON.md"))))))

(defn run-all! []
  (run-tests 'rs7-traffic-sweep-test))
