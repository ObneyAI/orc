(ns ai.obney.orc.ontology.cc9a-enforcement-gate-test
  "CC-9a — only VALIDATED claims may suppress retrieval (ADR 0022,
   invariant.OnlyValidatedClaimsEnforce).

   PROPAGATED FROM SPEC after the mid-arc weed sweep found a LIVE INVARIANT
   VIOLATION. CONTRACT — never weaken a test to make it pass; report it.

   THE DEFECT THIS CLOSES: `assemble-body` builds the body's `:avoid-when` from
   ALL guard claims and `:weaknesses` from ALL weakness claims, filtering only
   on `:kind` — `by-earned-support` sorts, it does not filter on `:status`.
   EL-2 then attaches those fields to a candidate and EL-5's `avoid-strings`
   consumes exactly them. So a `:candidate` claim — support 2, one episode,
   unproven by construction — is a live retrieval-suppression lever, and
   `get-enforcing-claims` (built in CC-7 precisely to prevent this) has zero
   production consumers.

   The split this asserts: a candidate claim stays VISIBLE (the model still
   sees it and can reason about it) and becomes NON-ENFORCING (it cannot move
   the ranking). The body is dual-purpose; one field cannot serve both."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.obney.orc.ontology.interface :as ontology]
            [ai.obney.orc.ontology.interface.schemas]
            [ai.obney.orc.ontology.core.commands]
            [ai.obney.orc.ontology.core.read-models]
            [ai.obney.orc.ontology.core.domain-penalty :as dp]
            [ai.obney.orc.evaluation.interface.schemas]
            [ai.obney.orc.evaluation.core.commands]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.query-processor.interface :as qp]
            [ai.obney.grain.pubsub.interface :as pubsub]
            [ai.obney.grain.todo-processor-v2.interface :as tp]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]
            [ai.obney.grain.time.interface :as time]))

(defn- create-context []
  (let [ps (pubsub/start {:type :core-async :topic-fn :event/type})
        event-store (es/start {:conn {:type :in-memory} :event-pubsub ps :logger nil})
        cache-dir (str "/tmp/cc9a-test-" (random-uuid))
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

(defn- ground-episodes! [ctx deltas]
  (doseq [[sheet-id tick-id] (distinct (mapcat :episodes deltas))]
    (cp/process-command
      (assoc ctx :command
             {:command/name :evaluation/record-judge-score
              :command/id (random-uuid) :command/timestamp (time/now)
              :sheet-id sheet-id :node-id (random-uuid) :tick-id tick-id
              :judge-name "coding-outcome" :judge-config {} :score 0.8
              :feedback (str "The turn applied the edit to src/util.clj and the "
                             "verification command exited 0, so the assessment is "
                             "grounded in the observed diff and command output.")
              :dimensions []}))))

(defn- delta [op overrides]
  (merge {:operation op :kind :guard :content "a guard"
          :episodes [(episode)] :from-legacy-corpus false}
         overrides))

(defn- record! [ctx target deltas]
  (ground-episodes! ctx deltas)
  (cp/process-command
    (assoc ctx :command
           {:command/name :ontology/record-claim-deltas
            :command/id (random-uuid) :command/timestamp (time/now)
            :granularity :tree-class :target-identifier target :deltas deltas
            :claim-set-version (ontology/get-claim-set-version ctx :tree-class target)})))

(defn- claim-id-for [ctx target content]
  (:claim-id (first (filter #(= content (:content %))
                            (ontology/get-claims ctx :tree-class target)))))

(defn- validated-guard!
  "A guard claim driven above the validation threshold with grounded episodes."
  [ctx target content]
  (record! ctx target [(delta :add {:content content})])
  (let [cid (claim-id-for ctx target content)]
    (dotimes [_ 6] (record! ctx target [(delta :support {:target-claim cid :content content})]))
    cid))

(defn- candidate-guard!
  "A guard claim left below the threshold — recorded, visible, unproven."
  [ctx target content]
  (record! ctx target [(delta :add {:content content})])
  (claim-id-for ctx target content))

(defn- enriched
  "The candidate map as EL-2 hands it to EL-5, for this target.

   Drives the PRIVATE `enrich-candidate-evidence` deliberately: that is the
   real seam between what a body carries and what the penalty consumes, and
   the only public alternative is the full rerank path, which needs ColBERT.
   Exercising the real consumer beats asserting on a hand-built map."
  [ctx target]
  [(#'ontology/enrich-candidate-evidence
     ctx {:document-identifier (str target)
          :content "a candidate"
          :document-metadata {:granularity :tree-class :target-id (str target)}})])

;; ---------------------------------------------------------------------------
;; invariant.OnlyValidatedClaimsEnforce — the closure
;; ---------------------------------------------------------------------------
(deftest an-unproven-claim-cannot-suppress-retrieval
  (with-test-ctx [ctx]
    (let [target (random-uuid)]
      (validated-guard! ctx target "avoid when the workspace has no test harness")
      (candidate-guard! ctx target "avoid when the file is very large")
      (let [c (first (enriched ctx target))
            avoid (dp/avoid-strings c)]
        (is (some #{"avoid when the workspace has no test harness"} avoid)
            "the VALIDATED guard reaches the penalty — enforcement still works")
        (is (not (some #{"avoid when the file is very large"} avoid))
            "the CANDIDATE guard does NOT — an unproven assertion must not be
             able to mute a behaviour, which is the whole point of the status
             field and the reason get-enforcing-claims exists")))))

(deftest a-candidate-claim-is-still-VISIBLE-to-the-model
  (with-test-ctx [ctx]
    (let [target (random-uuid)]
      (candidate-guard! ctx target "avoid when the file is very large")
      (let [b (ontology/get-description ctx :tree-class target)]
        (is (some #{"avoid when the file is very large"} (:avoid-when b))
            "non-enforcing is NOT invisible: the model must still see an
             unproven caution and be able to reason about it — suppressing it
             from the body would be a different bug, not a fix")))))

(deftest a-candidate-WEAKNESS-guard-cannot-enforce-either
  (with-test-ctx [ctx]
    (let [target (random-uuid)]
      ;; avoid-strings unions the body-level list with per-WEAKNESS guards, so
      ;; narrowing only the first would leave the second as an open door.
      (record! ctx target [(delta :add {:kind :weakness
                                        :content "claims success on an empty diff"
                                        :context-guard "avoid when the diff is empty"})])
      (let [c (first (enriched ctx target))
            avoid (dp/avoid-strings c)]
        (is (not (some #{"avoid when the diff is empty"} avoid))
            "a guard riding on an unproven WEAKNESS is equally unproven")))))

(deftest a-target-with-no-claims-behaves-exactly-as-before
  (with-test-ctx [ctx]
    (let [legacy (random-uuid)]
      (cp/process-command
        (assoc ctx :command
               {:command/name :ontology/record-tree-class-description
                :command/id (random-uuid) :command/timestamp (time/now)
                :target-id legacy
                :body {:capabilities [] :strengths [] :weaknesses []
                       :representative-uses []
                       :avoid-when ["avoid when the task is data processing"]
                       :summary "a legacy body" :version 1
                       :consolidated-from-event-count 3}}))
      (let [c (first (enriched ctx legacy))
            avoid (dp/avoid-strings c)]
        (is (some #{"avoid when the task is data processing"} avoid)
            "a body with no claims still enforces its guards — CC-12 has not
             migrated the corpus yet, and silently disarming every legacy
             target would be a far worse failure than the one being fixed")))))

(deftest promotion-turns-enforcement-on-for-a-claim-that-earns-it
  (with-test-ctx [ctx]
    (let [target (random-uuid)
          cid (candidate-guard! ctx target "avoid when there is no harness")]
      (is (not (some #{"avoid when there is no harness"}
                     (dp/avoid-strings (first (enriched ctx target)))))
          "unproven: silent")
      (dotimes [_ 6] (record! ctx target [(delta :support {:target-claim cid
                                                           :content "avoid when there is no harness"})]))
      (is (some #{"avoid when there is no harness"}
                (dp/avoid-strings (first (enriched ctx target))))
          "and once it is earned, the very same guard enforces — the gate is a
           threshold on evidence, not a permanent veto"))))
