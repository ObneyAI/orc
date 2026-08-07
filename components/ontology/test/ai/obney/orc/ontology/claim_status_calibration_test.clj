(ns ai.obney.orc.ontology.claim-status-calibration-test
  "CC-7 — IMPLEMENTER-AUTHORED, **not** propagated from the spec. These are
   durable guards for three properties the propagated contract file
   (`cc7_claim_status_test`) leaves unpinned, each of which was verified by
   hand during CC-7 and would otherwise be verified only once, by a human,
   and then lost.

   Measured, not assumed: replaying the CC-7 contract file against a
   deliberately mis-set threshold showed it pins the validation threshold only
   to the BAND [3, 7] — `accumulated-contradiction-demotes-a-validated-claim`
   fails below 3 and `a-well-supported-claim-...-becomes-validated` fails above
   7 — while saying nothing about where inside that band the line belongs. The
   choice of 5 is a calibration against CC-3's confidence curve, and a
   calibration nobody tests is a constant waiting to drift.

   1. THE TWO CALIBRATED NUMBERS AGREE. `:validated` must mean exactly
      'derived confidence has cleared the 0.6 enforcement floor'. Together
      these two numbers decide what the model SEES (confidence governs
      R-Inject's top-two rank) and what the ranker OBEYS (after CC-9, only a
      validated claim may suppress retrieval), so they must not be tunable
      independently by accident.

   2. `get-enforcing-claims` IS ORDERED. Its order is load-bearing for CC-9
      exactly as CC-3's body order is for EL-2: consumers downstream truncate
      in the order they are handed. The contract file's selection test has
      only ONE validated claim, so it cannot see order at all.

   3. ENFORCEMENT IS RE-EARNABLE. The contract file fires each transition edge
      once. The property that actually matters is that the edges keep firing —
      enforcement is continuously earned, not granted once and then lost once."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.obney.orc.ontology.interface :as ontology]
            [ai.obney.orc.ontology.interface.schemas]
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
            [ai.obney.grain.time.interface :as time]))

(def ^:private enforcement-confidence-floor
  "The confidence at which a claim is allowed to enforce. Stated here as a
   LITERAL on purpose: if this test read the implementation's constant it
   could not detect the implementation moving."
  0.6)

(defn- create-context []
  (let [ps (pubsub/start {:type :core-async :topic-fn :event/type})
        event-store (es/start {:conn {:type :in-memory} :event-pubsub ps :logger nil})
        cache-dir (str "/tmp/cc7-calib-" (random-uuid))
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

(defmacro ^:private with-test-ctx [[sym] & body]
  `(let [~sym (create-context)] (try ~@body (finally (stop-context ~sym)))))

(defn- episode [] [(random-uuid) (random-uuid)])

(defn- ground-episodes!
  "Seed one substantive judge score per occurrence, so CC-4's evidence guard
   resolves the episode instead of correctly refusing a fabricated one."
  [ctx deltas]
  (doseq [[sheet-id tick-id] (distinct (mapcat :episodes deltas))]
    (cp/process-command
      (assoc ctx :command
             {:command/name :evaluation/record-judge-score
              :command/id (random-uuid)
              :command/timestamp (time/now)
              :sheet-id sheet-id :node-id (random-uuid) :tick-id tick-id
              :judge-name "coding-outcome" :judge-config {}
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

(defn- delta [op overrides]
  (merge {:operation op :kind :weakness :content "a claim"
          :episodes [(episode)] :from-legacy-corpus false}
         overrides))

(defn- claim-by [ctx target content]
  (first (filter #(= content (:content %))
                 (ontology/get-claims ctx :tree-class target))))

(defn- claim-at-support!
  "Record a claim and drive it to exactly `n` net support with grounded
   episodes. Returns its claim-id."
  [ctx target content n]
  (record! ctx target [(delta :add {:content content})])
  (let [cid (:claim-id (claim-by ctx target content))
        ;; Drive to exactly n from wherever the SEED puts it, rather than
        ;; assuming a seed of 1. P-A moved the seed to 2 and this helper
        ;; overshot by one, which is the calibration leaking into the fixture.
        seeded (:support (claim-by ctx target content))]
    (dotimes [_ (max 0 (- n seeded))]
      (record! ctx target [(delta :support {:target-claim cid :content content})]))
    cid))

(defn- confidence-by-trait
  "Derived confidence per claim, read off the ASSEMBLED BODY — the same
   number R-Inject ranks on — rather than out of a private var."
  [ctx target]
  (into {} (map (juxt :trait :confidence))
        (:weaknesses (ontology/get-description ctx :tree-class target))))

;; ---------------------------------------------------------------------------
;; 1. The validation threshold IS the confidence floor's crossing point
;; ---------------------------------------------------------------------------

(deftest validated-is-exactly-confidence-at-or-above-the-enforcement-floor
  (with-test-ctx [ctx]
    (let [target (random-uuid)
          supports (range 1 9)]
      (doseq [n supports]
        (claim-at-support! ctx target (str "claim at support " n) n))
      (let [conf   (confidence-by-trait ctx target)
            claims (ontology/get-claims ctx :tree-class target)
            {validated :validated candidates :candidate} (group-by :status claims)]

        (testing "the fixture actually produced both statuses, so neither
                  assertion below is made over an empty set"
          (is (= 8 (count claims)))
          (is (seq validated))
          (is (seq candidates)))

        (testing "nothing enforces on confidence below the floor"
          (doseq [c validated]
            (is (>= (get conf (:content c)) enforcement-confidence-floor)
                (str "validated claim at support " (:support c)
                     " has confidence " (get conf (:content c))
                     ", below the 0.6 enforcement floor — the threshold and the
                      confidence curve have drifted apart"))))

        (testing "and everything above the floor DOES enforce: the threshold is
                  the crossing point, not merely consistent with it"
          (doseq [c candidates]
            (is (< (get conf (:content c)) enforcement-confidence-floor)
                (str "candidate claim at support " (:support c)
                     " already has confidence " (get conf (:content c))
                     " — the threshold is set higher than the curve justifies, so
                      the loop is learning things it is never allowed to act on"))))

        (testing "the crossing is where CC-3's curve puts it: c(s) = s/(s+3)
                  first clears 0.6 at s = 5"
          (is (= 5 (apply min (map :support validated)))
              "the least validated support IS the threshold, and it must be 5")
          (is (= 4 (apply max (map :support candidates)))))))))

;; ---------------------------------------------------------------------------
;; 2. The selection surface is ORDERED — CC-9 truncates in the order given
;; ---------------------------------------------------------------------------

(deftest enforcing-claims-arrive-strongest-first
  (with-test-ctx [ctx]
    (let [target (random-uuid)]
      ;; Recorded in DELIBERATELY WRONG order, so insertion order and support
      ;; order disagree and the assertion cannot pass by accident.
      (claim-at-support! ctx target "weakest enforcing" 5)
      (claim-at-support! ctx target "strongest enforcing" 8)
      (claim-at-support! ctx target "never enforces" 2)
      (claim-at-support! ctx target "middle enforcing" 6)
      (let [enforcing (ontology/get-enforcing-claims ctx :tree-class target)]
        (is (= ["strongest enforcing" "middle enforcing" "weakest enforcing"]
               (mapv :content enforcing))
            "get-enforcing-claims is ranked best-supported-first: its consumer is
             a ranker, and CC-3 established that consumers downstream truncate in
             the order they are handed rather than sorting for themselves")
        (is (= ["weakest enforcing" "strongest enforcing" "never enforces" "middle enforcing"]
               (mapv :content (ontology/get-claims ctx :tree-class target)))
            "while get-claims keeps insertion order — the two functions differ on
             purpose, and CC-9 must not assume they agree")
        (is (= (mapv :claim-id enforcing)
               (mapv :claim-id (ontology/get-enforcing-claims ctx :tree-class target)))
            "and the order is stable across reads")))))

;; ---------------------------------------------------------------------------
;; 3. Enforcement is CONTINUOUSLY earned — both edges fire more than once
;; ---------------------------------------------------------------------------

(deftest enforcement-can-be-lost-and-then-earned-again
  (with-test-ctx [ctx]
    (let [target  (random-uuid)
          content "an oscillating claim"
          cid     (claim-at-support! ctx target content 5)
          status  #(:status (claim-by ctx target content))
          support #(:support (claim-by ctx target content))]
      (is (= [:validated 5] [(status) (support)])
          "reaching the threshold earns enforcement")

      (record! ctx target [(delta :contradict {:target-claim cid :content content})])
      (is (= [:candidate 4] [(status) (support)])
          "one contradiction below the threshold withdraws it — demotion is not
           deletion, and it is not deferred to some later sweep")

      (record! ctx target [(delta :support {:target-claim cid :content content})])
      (is (= [:validated 5] [(status) (support)])
          "and it can be earned back: the candidate -> validated edge is not a
           one-shot promotion")

      (record! ctx target [(delta :contradict {:target-claim cid :content content})])
      (is (= :candidate (status)) "lost again")
      (record! ctx target [(delta :edit {:target-claim cid :content content})])
      (is (= [:validated 5] [(status) (support)])
          "an :edit reinforces as well as rewords, so it too can re-promote")

      (is (= [content] (mapv :content (ontology/get-enforcing-claims ctx :tree-class target)))
          "and the selection surface tracks the current status, not a high-water
           mark"))))
