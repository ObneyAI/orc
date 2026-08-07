(ns ai.obney.orc.ontology.cc3-body-assembly-test
  "CC-3 — the description body is ASSEMBLED from claims; every existing
   consumer keeps working (ADR 0021).

   PROPAGATED FROM SPEC: specs/ontology.allium. CONTRACT — never weaken a test
   to make it pass; report it as a finding instead.

   Obligations covered (allium plan ids):
     rule-success.AssembleDescriptionFromClaims
     rule-entity-creation.AssembleDescriptionFromClaims.1
     entity-fields.LivingDescription
     DescriptionHistoryIsAppendOnly      (existing invariant)
     DescriptionConfidenceIsBounded      (existing invariant)

   Written against the REAL APIs produced by CC-1 and CC-2 (see
   CC-1-PRODUCED-API.md / CC-2-PRODUCED-API.md in orc-sessions):
   record-claim-deltas + get-claims + get-claim-set-version + get-retired-claims;
   retirement makes a claim VANISH from :claims."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.obney.orc.ontology.interface :as ontology]
            [ai.obney.orc.ontology.interface.schemas :as schemas]
            [ai.obney.orc.ontology.core.commands]
            [ai.obney.orc.ontology.core.read-models]
            [ai.obney.orc.evaluation.interface.schemas]
            [ai.obney.orc.evaluation.core.commands]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.query-processor.interface :as qp]
            [ai.obney.grain.pubsub.interface :as pubsub]
            [ai.obney.grain.todo-processor-v2.interface :as tp]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]
            [ai.obney.grain.time.interface :as time]
            [malli.core :as m]))

(defn- create-context []
  (let [ps (pubsub/start {:type :core-async :topic-fn :event/type})
        event-store (es/start {:conn {:type :in-memory} :event-pubsub ps :logger nil})
        cache-dir (str "/tmp/cc3-test-" (random-uuid))
        cache (kv/start (lmdb/->KV-Store-LMDB {:storage-dir cache-dir :db-name "test"}))
        base-ctx {:event-store event-store :cache cache :tenant-id (random-uuid)
                  :event-pubsub ps
                  :command-registry (cp/global-command-registry)
                  :query-registry (qp/global-query-registry)
                  ::cache-dir cache-dir}
        processors (reduce-kv (fn [acc n {:keys [handler-fn topics]}]
                                (assoc acc n (tp/start {:event-pubsub ps :topics topics
                                                        :handler-fn handler-fn :context base-ctx})))
                              {} @tp/processor-registry*)]
    (assoc base-ctx :processors processors)))

(defn- stop-context [ctx]
  (doseq [[_ p] (:processors ctx)] (tp/stop p))
  (when-let [ps (:event-pubsub ctx)] (pubsub/stop ps))
  (when-let [c (:cache ctx)] (kv/stop c))
  (when-let [es (:event-store ctx)] (es/stop es))
  (when-let [dir (::cache-dir ctx)]
    (let [f (java.io.File. dir)]
      (when (.exists f) (doseq [c (.listFiles f)] (.delete c)) (.delete f)))))

(defmacro with-test-ctx [[sym] & body]
  `(let [~sym (create-context)] (try ~@body (finally (stop-context ~sym)))))

(defn- episode [] [(random-uuid) (random-uuid)])

(defn- delta [op overrides]
  (merge {:operation op :kind :weakness :content "a claim"
          :episodes [(episode)] :from-legacy-corpus false}
         overrides))


;; ---------------------------------------------------------------------------
;; PRODUCTION-FAITHFUL EPISODES (orchestrator fix, after CC-3 and CC-4 both
;; root-caused the same defect independently).
;;
;; These fixtures originally cited episodes built from fresh random uuids —
;; occurrences that never happened. That is not a production shape: a claim is
;; derived from a turn that actually ran and was judged, and CC-4's evidence
;; guard correctly refuses evidence it cannot resolve. Fabricated identifiers
;; hiding a real contract is the SJ-1 lesson repeating, so the fixtures are
;; made REALISTIC rather than the guard made permissive.
;; ---------------------------------------------------------------------------
(defn- ground-episodes!
  "Seed substantive judge evidence for every occurrence the deltas cite."
  [ctx deltas]
  (doseq [[sheet-id tick-id] (distinct (mapcat :episodes deltas))]
    (cp/process-command
      (assoc ctx :command
             {:command/name :evaluation/record-judge-score
              :command/id (random-uuid)
              :command/timestamp (time/now)
              :sheet-id sheet-id
              :node-id (random-uuid)
              :tick-id tick-id
              :judge-name "coding-outcome"
              :judge-config {}
              :score 0.8
              :feedback (str "The turn applied the edit to src/util.clj and the "
                             "verification command exited 0, so the assessment is "
                             "grounded in the observed diff and command output.")
              :dimensions []}))))

(defn- record! [ctx target deltas]
  (ground-episodes! ctx deltas)
  (cp/process-command
    (assoc ctx :command
           {:command/name :ontology/record-claim-deltas
            :command/id (random-uuid)
            :command/timestamp (time/now)
            :granularity :tree-class
            :target-identifier target
            :deltas deltas
            :claim-set-version (ontology/get-claim-set-version ctx :tree-class target)})))

(defn- body [ctx target] (ontology/get-description ctx :tree-class target))

(defn- claim-id-for [ctx target content]
  (:claim-id (first (filter #(= content (:content %))
                            (ontology/get-claims ctx :tree-class target)))))

;; ---------------------------------------------------------------------------
;; rule-success.AssembleDescriptionFromClaims — every claim kind reaches the
;; body section its consumers read.
;; ---------------------------------------------------------------------------
(deftest each-claim-kind-is-assembled-into-the-body-section-consumers-read
  (with-test-ctx [ctx]
    (let [target (random-uuid)]
      (record! ctx target
               [(delta :add {:kind :capability :content "reads a file before editing it"})
                (delta :add {:kind :strength :content "verifies with a real command"
                             :context-guard "the workspace has a test harness"
                             :recommendation "run the targeted test after each edit"})
                (delta :add {:kind :weakness :content "claims success on an empty diff"
                             :context-guard "the edit produced no changes"
                             :recommendation "re-read the file and retry the patch"})
                (delta :add {:kind :guard :content "avoid when no verification command exists"})
                (delta :add {:kind :representative-use :content "targeted bug fix in a utility module"})])
      (let [b (body ctx target)]
        (is (some? b) "a description exists once claims exist")
        (is (some #{"reads a file before editing it"} (:capabilities b)))
        (is (some #{"targeted bug fix in a utility module"} (:representative-uses b)))
        (is (some #{"avoid when no verification command exists"} (:avoid-when b))
            "guard claims land in the body-level avoid-when the domain penalty reads")
        (is (= 1 (count (:strengths b))))
        (is (= 1 (count (:weaknesses b))))))))

;; ---------------------------------------------------------------------------
;; entity-fields.LivingDescription — the assembled body must satisfy the
;; SHIPPED schema, because ~16 consumers already read that shape.
;; ---------------------------------------------------------------------------
(deftest the-assembled-body-satisfies-the-shipped-description-body-schema
  (with-test-ctx [ctx]
    (let [target (random-uuid)]
      (record! ctx target
               [(delta :add {:kind :strength :content "a strength"
                             :context-guard "when X" :recommendation "do Y"})
                (delta :add {:kind :capability :content "a capability"})])
      (let [b (body ctx target)]
        (is (m/validate schemas/description-body b)
            (str "assembled body must validate against the shipped schema — "
                 "R-Inject, EL-2, EL-5, harvest and reindex all read this shape. "
                 (pr-str (m/explain schemas/description-body b))))
        (is (string? (:summary b)))
        (is (int? (:version b)))
        (is (int? (:consolidated-from-event-count b)))))))

;; ---------------------------------------------------------------------------
;; Strength/weakness entries must be principle-shaped so R-Inject's
;; confidence sort and display floor keep working.
;; ---------------------------------------------------------------------------
(deftest strength-and-weakness-entries-are-principle-shaped-with-derived-confidence
  (with-test-ctx [ctx]
    (let [target (random-uuid)]
      (record! ctx target [(delta :add {:kind :strength :content "verifies before claiming done"
                                        :context-guard "a harness exists"
                                        :recommendation "run it"})])
      (let [e (first (:strengths (body ctx target)))]
        (is (= "verifies before claiming done" (:trait e))
            "claim content becomes the entry's trait")
        (is (= "a harness exists" (:good-when e)))
        (is (= "run it" (:recommended-pattern e)))
        (is (number? (:confidence e)) "confidence is DERIVED from support, not asserted by an LLM")
        (is (int? (:evidence-count e)))))))

(deftest derived-confidence-is-bounded-and-rises-with-support
  (with-test-ctx [ctx]
    (let [target (random-uuid)]
      (record! ctx target [(delta :add {:kind :strength :content "growing claim"})])
      (let [cid (claim-id-for ctx target "growing claim")
            c0 (:confidence (first (:strengths (body ctx target))))]
        (dotimes [_ 5] (record! ctx target [(delta :support {:target-claim cid})]))
        (let [c1 (:confidence (first (:strengths (body ctx target))))]
          (dotimes [_ 6] (record! ctx target [(delta :support {:target-claim cid})]))
          (let [c2 (:confidence (first (:strengths (body ctx target))))]
            (is (<= 0.0 c0 1.0) "confidence stays within bounds at low support")
            (is (<= 0.0 c1 1.0) "confidence stays within bounds at high support")
            ;; STRENGTHENED after CC-3 reported the original as vacuous: it
            ;; asserted (>= c1 c0), which ANY CONSTANT satisfies — so the whole
            ;; curve could have been (constantly 0.5) with the suite still
            ;; green, and derived confidence is the single property this slice
            ;; exists to establish.
            (is (< c0 c1) "confidence must STRICTLY rise with support")
            (is (< c1 c2) "and keep rising — a constant is not a curve")
            (is (< c2 1.0)
                "and never reach certainty: no finite pile of episodes proves a
                 claim about a probabilistic system")))))))

;; ---------------------------------------------------------------------------
;; A retired claim must not appear in the assembled body.
;; ---------------------------------------------------------------------------
(deftest a-retired-claim-disappears-from-the-assembled-body
  (with-test-ctx [ctx]
    (let [target (random-uuid)]
      (record! ctx target [(delta :add {:kind :weakness :content "contested weakness"})
                           (delta :add {:kind :weakness :content "durable weakness"})])
      (let [doomed (claim-id-for ctx target "contested weakness")
            keeper (claim-id-for ctx target "durable weakness")]
        (record! ctx target [(delta :support {:target-claim keeper})])
        ;; contradict until gone — seed-independent (P-A moved the seed 1 -> 2)
        (loop [guard 0]
          (when (and (< guard 10)
                     (some #(= doomed (:claim-id %))
                           (ontology/get-claims ctx :tree-class target)))
            (record! ctx target [(delta :contradict {:target-claim doomed})])
            (recur (inc guard))))
        (let [traits (set (map :trait (:weaknesses (body ctx target))))]
          (is (contains? traits "durable weakness"))
          (is (not (contains? traits "contested weakness"))
              "a retired claim leaves the body, not just the claim set"))))))

;; ---------------------------------------------------------------------------
;; DescriptionHistoryIsAppendOnly + version advance
;; ---------------------------------------------------------------------------
(deftest the-description-version-advances-and-history-is-append-only
  (with-test-ctx [ctx]
    (let [target (random-uuid)]
      (record! ctx target [(delta :add {:kind :capability :content "first"})])
      (let [v1 (:version (body ctx target))
            h1 (count (ontology/get-description-history ctx :tree-class target))]
        (record! ctx target [(delta :add {:kind :capability :content "second"})])
        (let [v2 (:version (body ctx target))
              h2 (count (ontology/get-description-history ctx :tree-class target))]
          (is (> v2 v1) "a claim-set change advances the description version")
          (is (>= h2 h1) "history never shrinks")
          (is (some #{"first"} (:capabilities (body ctx target)))
              "the earlier claim survives the later change — this is the whole point"))))))

;; ---------------------------------------------------------------------------
;; Legacy coexistence: a body recorded the old way is still readable, and is
;; NOT clobbered by the claim-assembled path.
;; ---------------------------------------------------------------------------
(deftest legacy-recorded-bodies-remain-readable-alongside-assembled-ones
  (with-test-ctx [ctx]
    (let [legacy (random-uuid)
          assembled (random-uuid)]
      (cp/process-command
        (assoc ctx :command
               {:command/name :ontology/record-tree-class-description
                :command/id (random-uuid)
                :command/timestamp (time/now)
                :target-id legacy
                :body {:capabilities ["legacy capability"] :strengths [] :weaknesses []
                       :representative-uses [] :avoid-when []
                       :summary "written before claims existed"
                       :version 1 :consolidated-from-event-count 3}}))
      (record! ctx assembled [(delta :add {:kind :capability :content "assembled capability"})])
      (is (= ["legacy capability"] (:capabilities (body ctx legacy))))
      (is (some #{"assembled capability"} (:capabilities (body ctx assembled))))
      (is (not= (:summary (body ctx legacy)) (:summary (body ctx assembled)))
          "the two paths do not clobber one another"))))
