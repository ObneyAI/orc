(ns ai.obney.orc.ontology.cc2-retirement-fact-test
  "CC-2 — the ClaimRetired FACT, and the rebuild that must reproduce it.

   NOT propagated from spec: this is the implementer's durable cover for two
   things `cc2_claim_operations_test.clj` proves only by absence.

   1. The contract test asserts a retired claim is GONE from `get-claims`.
      That alone is also what a silent delete would look like. The spec's
      `RetireExhaustedClaim` ensures a ClaimRetired FACT, so the fact has to
      be observable — otherwise 'retired' and 'deleted' are the same
      observation and the load-bearing property (no single judgement can
      erase knowledge) is untestable.
   2. Retirement is the first operation that REMOVES from the fold, which
      makes it the first real risk to rebuild determinism. Folding the raw
      log must reproduce the projection exactly, retirements included."
  (:require [ai.obney.orc.ontology.core.read-models :as read-models]
            [ai.obney.orc.ontology.core.logic :as logic]
            [ai.obney.orc.ontology.core.commands :as commands]
            [clojure.test :refer [deftest testing is]]
            [ai.obney.orc.ontology.interface :as ontology]
            [ai.obney.orc.ontology.interface.schemas :as schemas]
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
        cache-dir (str "/tmp/cc2-retire-test-" (random-uuid))
        cache (kv/start (lmdb/->KV-Store-LMDB {:storage-dir cache-dir :db-name "test"}))
        tenant-id (random-uuid)
        base-ctx {:event-store event-store
                  :cache cache
                  :tenant-id tenant-id
                  :event-pubsub ps
                  :command-registry (cp/global-command-registry)
                  :query-registry (qp/global-query-registry)
                  ::cache-dir cache-dir}
        processors (reduce-kv
                     (fn [acc proc-name {:keys [handler-fn topics]}]
                       (assoc acc proc-name
                              (tp/start {:event-pubsub ps :topics topics
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
      (when (.exists f)
        (doseq [c (.listFiles f)] (.delete c))
        (.delete f)))))

(defmacro with-test-ctx [[sym] & body]
  `(let [~sym (create-context)]
     (try ~@body (finally (stop-context ~sym)))))

(defn- episode [] [(random-uuid) (random-uuid)])

(defn- delta [op overrides]
  (merge {:operation op
          :kind :weakness
          :content "a claim"
          :episodes [(episode)]
          :from-legacy-corpus false}
         overrides))


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

(defn- seed-claim! [ctx target content]
  (record! ctx target [(delta :add {:content content})])
  (:claim-id (first (filter #(= content (:content %))
                            (ontology/get-claims ctx :tree-class target)))))

(defn- retire!
  "Contradict until the claim is gone, then return. Written as a loop rather
   than a fixed count DELIBERATELY: encoding \"one contradiction retires\" made
   these fixtures depend on the seed value, and they broke the moment P-A's
   finding moved it from 1 to 2. A fixture should assert the property, not the
   calibration."
  ([ctx target cid] (retire! ctx target cid nil))
  ([ctx target cid final-episode]
   (loop [guard 0]
     (when (and (< guard 10)
                (some #(= cid (:claim-id %))
                      (ontology/get-claims ctx :tree-class target)))
       (let [last? (= 1 (count (filter #(= cid (:claim-id %))
                                       (ontology/get-claims ctx :tree-class target))))
             ep (when (and last? final-episode) final-episode)]
         (record! ctx target [(delta :contradict
                                     (cond-> {:target-claim cid}
                                       ep (assoc :episodes [ep])))]))
       (recur (inc guard))))))

;; ---------------------------------------------------------------------------
;; The ClaimRetired fact
;; ---------------------------------------------------------------------------
(deftest retirement_leaves_an_observable_fact_not_a_silent_deletion
  (with-test-ctx [ctx]
    (let [target (random-uuid)
          cid (seed-claim! ctx target "an insight that gets contradicted")
          killing-ep (episode)]
      (is (empty? (ontology/get-retired-claims ctx :tree-class target))
          "nothing has retired yet")
      (retire! ctx target cid killing-ep)
      (let [retirements (ontology/get-retired-claims ctx :tree-class target)
            r (first retirements)]
        (is (= 1 (count retirements)) "exactly one retirement fact")
        (is (= :support-exhausted (:reason r))
            "the ONLY reason a claim may leave — there is no delete operation")
        (is (= cid (:claim-id (:claim r)))
            "the fact names the claim, so what was retired stays knowable")
        (is (= "an insight that gets contradicted" (:content (:claim r)))
            "the claim is snapshotted, not just its id")
        (is (some #{killing-ep} (:contradicting-episodes (:claim r)))
            "the contradiction that finished it is on the record")
        (is (m/validate schemas/claim-retirement r)
            "the fact matches its declared shape")
        (is (empty? (ontology/get-claims ctx :tree-class target))
            "and it is gone from the live claim set")))))

(deftest retirement_facts_accumulate_and_never_shrink
  (with-test-ctx [ctx]
    (let [target (random-uuid)]
      (doseq [n ["first" "second"]]
        (let [cid (seed-claim! ctx target n)]
          (retire! ctx target cid)))
      (is (= ["first" "second"]
             (map #(get-in % [:claim :content])
                  (ontology/get-retired-claims ctx :tree-class target)))
          "retirements accrue in order; the record of what we stopped believing
           only ever grows")
      (testing "a delta naming an already-retired claim changes nothing"
        (let [gone (:claim-id (:claim (first (ontology/get-retired-claims
                                               ctx :tree-class target))))]
          (record! ctx target [(delta :support {:target-claim gone})])
          (record! ctx target [(delta :contradict {:target-claim gone})])
          (is (= 2 (count (ontology/get-retired-claims ctx :tree-class target)))
              "a ghost reference does not re-retire or resurrect anything")
          (is (empty? (ontology/get-claims ctx :tree-class target))))))))

(deftest folding_the_raw_log_reproduces_the_projection_retirements_included
  (with-test-ctx [ctx]
    (let [target (random-uuid)
          doomed (seed-claim! ctx target "doomed")
          survivor (seed-claim! ctx target "survivor")]
      (record! ctx target [(delta :support {:target-claim survivor})])
      (record! ctx target [(delta :edit {:target-claim survivor
                                         :kind :guard
                                         :content "survivor, reworded"})])
      (record! ctx target [(delta :contradict {:target-claim survivor})])
      (record! ctx target [(delta :contradict {:target-claim doomed})])
      (record! ctx target [(delta :contradict {:target-claim doomed})])
      (let [events (into [] (es/read (:event-store ctx)
                                     {:tenant-id (:tenant-id ctx)
                                      :types #{:ontology/claim-deltas-recorded}}))
            rebuilt (logic/descriptions {} events)
            node (get-in rebuilt [:tree-class target])]
        (is (= 7 (count events)) "every delta batch is in the log")
        (is (= (ontology/get-claims ctx :tree-class target) (:claims node))
            "a rebuild from the log reproduces the live claim set exactly")
        (is (= (ontology/get-retired-claims ctx :tree-class target)
               (:retired-claims node))
            "including the retirements — a removing fold is still deterministic")
        (is (= (ontology/get-claim-set-version ctx :tree-class target)
               (:claim-set-version node))
            "and the CAS version")
        (is (= ["survivor, reworded"] (map :content (:claims node)))
            "the surviving claim kept its identity through an edit and a
             contradiction; only the exhausted one left")))))
