(ns ai.obney.orc.ontology.cc23-ranked-candidates-test
  "CC-23 (contract TaskClassification) — schema-level guards for the two
   invariants, plus classify-task branch units for :assigned-via and the
   reduced pre-gate snapshot.

   The payload bound is STRUCTURAL: `ranked-candidate` is a closed map
   (no :content / :summary can validate) and `ranked-candidates` carries
   {:max 5} (the retrieval k). These tests are the durable guards that a
   future 'helpful' field addition cannot silently reopen the CC-21
   payload hole."
  (:require [clojure.test :refer [deftest testing is]]
            [malli.core :as m]
            [ai.obney.orc.ontology.interface :as ontology]
            [ai.obney.orc.ontology.interface.schemas :as schemas]
            [ai.obney.orc.ontology.core.task-classifier :as tc]
            [ai.obney.orc.ontology.core.commands]
            [ai.obney.orc.ontology.core.reranker :as reranker]
            [ai.obney.grain.schema-util.interface :as schema-util]))

(defn- registered [k] (get @schema-util/registry* k))

(def ^:private valid-entry
  {:target-id (str (random-uuid))
   :granularity :tree-class
   :rerank-source :reranker
   :fitness-score 0.9
   :score 0.8})

(def ^:private fallback-entry
  ;; Fallback path: reranker never scored — :fitness-score is ABSENT
  ;; (omit-not-nil), not nil.
  {:target-id (str (random-uuid))
   :granularity :tree-fingerprint
   :rerank-source :colbert-fallback
   :score 0.4})

(defn- deferred-event [ranked]
  {:source-sheet-id (random-uuid)
   :source-tick-id (random-uuid)
   :source-node-id (random-uuid)
   :fallback-source :colbert-fallback
   :ranked-candidates ranked
   :reasoning "reranker fell back"
   :deferred-at "2026-08-11T00:00:00Z"})

;; =============================================================================
;; Deferral event schema — DeferralIsVisible
;; =============================================================================

(deftest deferred-event-schema-is-registered-and-validates
  (let [schema (registered :ontology/task-classification-deferred)]
    (is (some? schema) "the deferral event schema is registered")
    (is (m/validate schema (deferred-event [valid-entry fallback-entry]))
        (str "a well-formed deferral event validates. Explain: "
             (pr-str (m/explain schema (deferred-event [valid-entry fallback-entry])))))
    (is (not (m/validate schema (dissoc (deferred-event [valid-entry]) :ranked-candidates)))
        ":ranked-candidates is REQUIRED on the deferral (the snapshot is the point)")
    (is (not (m/validate schema (dissoc (deferred-event [valid-entry]) :fallback-source)))
        ":fallback-source is REQUIRED")))

(deftest deferral-fallback-source-is-a-closed-set
  (let [schema (registered :ontology/task-classification-deferred)]
    (is (not (m/validate schema (assoc (deferred-event []) :fallback-source :reranker)))
        ":reranker is NOT a fallback source — the closed set admits only the two fallback flavours")
    (is (not (m/validate schema (assoc (deferred-event []) :fallback-source "colbert-fallback")))
        "stringly values do not validate (closed keyword set)")
    (is (m/validate schema (assoc (deferred-event []) :fallback-source :timeout-fallback))
        ":timeout-fallback (RR-1) is a member")))

;; =============================================================================
;; Payload bound — closed entry map + {:max 5}
;; =============================================================================

(deftest ranked-candidate-entry-is-closed-against-description-content
  (let [schema (registered :ontology/task-classification-deferred)]
    (is (not (m/validate schema (deferred-event [(assoc valid-entry :content "leak")])))
        "an entry carrying :content does NOT validate (the CC-21 bound, structurally enforced)")
    (is (not (m/validate schema (deferred-event [(assoc valid-entry :summary "leak")])))
        "an entry carrying :summary does NOT validate")
    (is (not (m/validate schema (deferred-event [(assoc valid-entry :reasoning "prose")])))
        "an entry carrying description text of any kind does NOT validate")))

(deftest ranked-candidates-vector-is-bounded-to-retrieval-k
  (let [schema (registered :ontology/task-classification-deferred)
        six (vec (repeat 6 valid-entry))
        five (vec (repeat 5 valid-entry))]
    (is (= 5 tc/classify-retrieval-k)
        "the schemas' {:max 5} mirrors the classifier's retrieval k — if this fails, re-align BOTH")
    (is (m/validate schema (deferred-event five)) "k entries validate")
    (is (not (m/validate schema (deferred-event six)))
        "k+1 entries do NOT validate — length <= k is the stated bound")))

(deftest ranked-candidate-bound-is-stated-in-the-schema-docstring
  (doseq [v [#'schemas/ranked-candidate #'schemas/ranked-candidates]]
    (is (re-find #"(?i)content" (or (:doc (meta v)) ""))
        (str (pr-str v) " docstring states the no-description-content bound"))))

;; =============================================================================
;; Classified event / command — DecidedRankingIsRecorded, additively
;; =============================================================================

(defn- legacy-classified-event []
  {:source-sheet-id (random-uuid)
   :source-tick-id (random-uuid)
   :source-node-id (random-uuid)
   :assigned-tree-id (random-uuid)
   :confidence 0.85
   :top-candidates []
   :reasoning "x"
   :classified-at "2026-08-11T00:00:00Z"
   :was-fresh-mint? false})

(deftest classified-event-accepts-new-fields-and-replays-legacy
  (let [schema (registered :ontology/task-classified)]
    (is (m/validate schema (legacy-classified-event))
        "REPLAY TOLERANCE: every pre-CC-23 event (no new fields) still validates")
    (is (m/validate schema (assoc (legacy-classified-event)
                                  :ranked-candidates [valid-entry fallback-entry]
                                  :assigned-via :match))
        (str "an event WITH the CC-23 fields validates. Explain: "
             (pr-str (m/explain schema (assoc (legacy-classified-event)
                                              :ranked-candidates [valid-entry]
                                              :assigned-via :match)))))))

(deftest assigned-via-is-a-closed-set
  (let [schema (registered :ontology/task-classified)]
    (doseq [via [:match :bundle :walk-down :mint]]
      (is (m/validate schema (assoc (legacy-classified-event) :assigned-via via))
          (str via " is a member of the closed provenance set")))
    (is (not (m/validate schema (assoc (legacy-classified-event) :assigned-via :other)))
        "an undeclared provenance does NOT validate (closed set)")
    (is (not (m/validate schema (assoc (legacy-classified-event) :assigned-via "match")))
        "stringly provenance does NOT validate")))

(deftest assign-task-class-command-accepts-new-fields-and-legacy
  (let [schema (registered :ontology/assign-task-class)
        legacy {:source-sheet-id (random-uuid)
                :source-tick-id (random-uuid)
                :source-node-id (random-uuid)
                :assigned-tree-id (random-uuid)
                :confidence 0.9
                :top-candidates []
                :reasoning "x"
                :was-fresh-mint? false}]
    (is (m/validate schema legacy)
        "legacy command (no CC-23 fields) still validates — omit-not-nil producers stay valid")
    (is (m/validate schema (assoc legacy
                                  :ranked-candidates [valid-entry]
                                  :assigned-via :bundle))
        "command with the CC-23 fields validates")
    (is (not (m/validate schema (assoc legacy :ranked-candidates [(assoc valid-entry :content "leak")])))
        "the command schema enforces the same payload bound")))

(deftest deferral-command-schema-is-registered
  (let [schema (registered :ontology/record-task-classification-deferral)]
    (is (some? schema) "the deferral command schema is registered")
    (is (m/validate schema {:source-sheet-id (random-uuid)
                            :source-tick-id (random-uuid)
                            :source-node-id (random-uuid)
                            :fallback-source :colbert-fallback
                            :ranked-candidates [fallback-entry]
                            :reasoning "deferred"})
        "a well-formed deferral command validates")))

;; =============================================================================
;; classify-task branch units — :assigned-via provenance + reduced snapshot
;; =============================================================================

(defn- tree-class-candidate [id fitness score]
  {:content (str "description content for " id " — must be stripped")
   :score score
   :document-id (str id)
   :document-metadata {:granularity :tree-class
                       :target-id (str id)
                       :confidence 0.9}
   :reasoning "candidate reasoning"
   :fitness-score fitness
   :rerank-source :reranker})

(deftest classify-task-bundle-branch-reports-assigned-via-bundle
  (testing "in-band near-miss to an existing tree-class → :assigned-via :bundle + reduced snapshot"
    (let [class-id (random-uuid)
          candidates [(tree-class-candidate class-id 0.65 0.8)]]
      (with-redefs [ontology/search-descriptions (fn [_ _] candidates)
                    tc/get-consolidation-total* (fn [_ _ _] 0)]
        (let [result (ontology/classify-task {} {:task-signature "x" :threshold 0.7})]
          (is (= :bundle (:assigned-via result)) "the bundle branch reports :bundle")
          (is (= class-id (:assigned-tree-id result)) "sanity: bundled onto the existing class")
          (is (false? (:was-fresh-mint? result)))
          (is (= [(str class-id)] (mapv :target-id (:ranked-candidates result)))
              "the snapshot carries the candidate identity")
          (is (not-any? #(contains? % :content) (:ranked-candidates result))
              "the snapshot is content-free at the classify-task level too"))))))

(deftest classify-task-uncertain-branch-has-no-assigned-via
  (testing ":outcome :uncertain assigns NOTHING → no :assigned-via key at all (omit, not nil)"
    (let [candidates [{:content "x" :score 0.3 :document-id "a"
                       :document-metadata {:granularity :tree-class
                                           :target-id (str (random-uuid))
                                           :confidence 0.5}
                       :reasoning nil
                       :fitness-score nil
                       :rerank-source :colbert-fallback}]]
      (with-redefs [ontology/search-descriptions (fn [_ _] candidates)
                    tc/get-consolidation-total* (fn [_ _ _] 0)]
        (let [result (ontology/classify-task {} {:task-signature "x" :threshold 0.7})]
          (is (= :uncertain (:outcome result)) "sanity: the fallback deferred")
          (is (not (contains? result :assigned-via))
              "nothing was assigned, so there is no provenance to record")
          (is (= 1 (count (:ranked-candidates result)))
              "the snapshot still records what the decision saw")
          (is (not (contains? (first (:ranked-candidates result)) :fitness-score))
              "a never-scored candidate OMITS :fitness-score (not nil) — 'never scored' is distinguishable from 'scored 0.0'")
          (is (= :colbert-fallback (:rerank-source (first (:ranked-candidates result))))
              "the fallback source is readable off the snapshot"))))))

(deftest classify-task-walk-down-branch-reports-assigned-via-walk-down
  (testing "moderate-confidence top-1 walks down to a tighter descendant → :assigned-via :walk-down"
    (let [parent-id (random-uuid)
          child-id (random-uuid)
          candidates [(tree-class-candidate parent-id 0.8 0.7)]]
      (with-redefs [ontology/search-descriptions (fn [_ _] candidates)
                    tc/get-consolidation-total* (fn [_ _ _] 0)
                    ontology/get-narrower-concepts
                    (fn [_ uri]
                      (if (= uri (str "tree-class:" parent-id))
                        #{(str "tree-class:" child-id)}
                        #{}))
                    ontology/get-description (fn [_ _ _] {:summary "tighter child pattern"})
                    reranker/rerank! (fn [_ _]
                                       [{:document-id (str child-id)
                                         :reasoning "tighter fit"
                                         :fitness-score 0.9}])]
        (let [result (ontology/classify-task {} {:task-signature "x" :threshold 0.7})]
          (is (= child-id (:assigned-tree-id result)) "sanity: the walk descended to the child")
          (is (= :walk-down (:assigned-via result))
              "a descent to a deeper EXISTING class is :walk-down")
          (is (= parent-id (:parent-tree-id result)) "sanity: parent carried")
          (is (= [(str parent-id)] (mapv :target-id (:ranked-candidates result)))
              "the snapshot is the RETRIEVAL ranking the decision started from"))))))

(deftest classify-task-match-and-mint-branches-report-provenance
  (testing "confident top-1 → :match; nothing above threshold and below the bundle band → :mint"
    (let [class-id (random-uuid)]
      (with-redefs [ontology/search-descriptions
                    (fn [_ _] [(tree-class-candidate class-id 0.95 0.9)])
                    tc/get-consolidation-total* (fn [_ _ _] 0)]
        (is (= :match (:assigned-via (ontology/classify-task {} {:task-signature "x" :threshold 0.7})))
            "confident top-1 (>= specificity) is :match"))
      (with-redefs [ontology/search-descriptions
                    (fn [_ _] [(tree-class-candidate class-id 0.2 0.1)])
                    tc/get-consolidation-total* (fn [_ _ _] 0)]
        (let [result (ontology/classify-task {} {:task-signature "x" :threshold 0.7})]
          (is (= :mint (:assigned-via result)) "below threshold + below the bundle band is :mint")
          (is (true? (:was-fresh-mint? result))))))))
