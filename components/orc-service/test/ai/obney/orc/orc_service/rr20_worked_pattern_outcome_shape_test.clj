(ns ai.obney.orc.orc-service.rr20-worked-pattern-outcome-shape-test
  "RR-20 — propagated from `specs/orc-service.allium`
   (`entity-optional.CampaignIteration.emitted_shape`,
   `invariant.RecordedTreesCarryTheirShape`) and governed by
   `specs/ontology.allium` `WorkedPatternsAreProvenNotMerelyRecent`:

     The pattern recorded for a class is one a campaign SUCCEEDED with. A
     shape that failed is recorded as having failed and never displaces one
     that worked, and a later attempt does not overwrite an earlier proven
     one merely by being later. A class that genuinely succeeds with more
     than one shape keeps each of them, so what is offered as proven is
     distinguishable from what was merely most recent.

   Generated tests are contract: never weakened to pass. Real Grain: the real
   bookend command, the real registered enrichment processor; assertions read
   the claim set, the assembled body and `harvest-body` back."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [clojure.string :as str]
            [malli.core :as m]
            [ai.obney.orc.llm.interface :as llm]
            [ai.obney.orc.orc-service.interface.schemas :as orc-schemas]
            [ai.obney.orc.orc-service.core.commands]
            [ai.obney.orc.orc-service.core.read-models]
            [ai.obney.orc.orc-service.core.rlm-fingerprint :as rlm-fingerprint]
            [ai.obney.orc.ontology.interface :as ontology]
            [ai.obney.orc.ontology.interface.schemas]
            [ai.obney.orc.ontology.core.commands]
            [ai.obney.orc.ontology.core.read-models]
            [ai.obney.orc.ontology.core.todo-processors]
            [ai.obney.orc.ontology.core.harvest :as harvest]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.query-processor.interface :as qp]
            [ai.obney.grain.pubsub.interface :as pubsub]
            [ai.obney.grain.todo-processor-v2.interface :as tp]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]
            [ai.obney.grain.time.interface :as time]))

(defn- stub-predict-fixture [f]
  (with-redefs [llm/predict (fn [_provider _module _inputs _options]
                              {:outputs {:operations []}
                               :usage {:total-tokens 1}
                               :model "stub"})]
    (f)))

(use-fixtures :each stub-predict-fixture)

;; -----------------------------------------------------------------------------
;; entity-optional.CampaignIteration.emitted_shape
;; invariant.RecordedTreesCarryTheirShape
;; -----------------------------------------------------------------------------

(def ^:private base-record
  {:iteration-index 0 :attempt-ordinal 0 :status :success
   :started-at "2026-09-10T00:00:00Z" :completed-at "2026-09-10T00:00:01Z"
   :duration-ms 1000 :code "(def x 1)" :generated-code-recorded? true})

(deftest campaign-iteration-emitted-shape-is-optional-and-recorded-trees-carry-it
  (testing "emitted_shape accepts null when no tree was recorded and non-null when one was"
    (is (m/validate orc-schemas/researcher-iteration-record
                    (assoc base-record :emitted-tree-recorded? false))
        "no recorded tree → shape may be absent")
    (is (m/validate orc-schemas/researcher-iteration-record
                    (assoc base-record :emitted-tree-recorded? true
                           :emitted-tree [:sequence [:final {:keys [:x]}]]
                           :emitted-tree-source "[:sequence [:final {:keys [:x]}]]"
                           :tree-fingerprint "shape-1"))
        "recorded tree with its shape validates"))
  (testing "RecordedTreesCarryTheirShape: emitted_tree_recorded implies emitted_shape != null"
    (is (not (m/validate orc-schemas/researcher-iteration-record
                         (assoc base-record :emitted-tree-recorded? true
                                :emitted-tree [:sequence [:final {:keys [:x]}]]
                                :emitted-tree-source "[:sequence [:final {:keys [:x]}]]")))
        "a recorded tree without a shape is not a valid iteration record")
    (is (not (m/validate orc-schemas/researcher-iteration-record
                         (assoc base-record :emitted-tree-recorded? true
                                :emitted-tree [:sequence [:final {:keys [:x]}]]
                                :emitted-tree-source "[:sequence [:final {:keys [:x]}]]"
                                :tree-fingerprint "")))
        "an empty shape does not satisfy the invariant")))

;; -----------------------------------------------------------------------------
;; WorkedPatternsAreProvenNotMerelyRecent — the behavioural bridge
;; -----------------------------------------------------------------------------

(defn- create-context []
  (let [ps (pubsub/start {:type :core-async :topic-fn :event/type})
        event-store (es/start {:conn {:type :in-memory} :event-pubsub ps :logger nil})
        cache-dir (str "/tmp/rr20-" (random-uuid))
        cache (kv/start (lmdb/->KV-Store-LMDB {:storage-dir cache-dir :db-name "test"}))
        base-ctx {:event-store event-store :cache cache :tenant-id (random-uuid)
                  :event-pubsub ps
                  :command-registry (cp/global-command-registry)
                  :query-registry (qp/global-query-registry)
                  ::cache-dir cache-dir}
        processors (reduce-kv
                    (fn [acc n {:keys [handler-fn topics]}]
                      (assoc acc n (tp/start {:event-pubsub ps :topics topics
                                              :handler-fn handler-fn :context base-ctx})))
                    {} @tp/processor-registry*)]
    (assoc base-ctx :processors processors)))

(defn- stop-context [ctx]
  (doseq [[_ p] (:processors ctx)] (tp/stop p))
  (when-let [ps (:event-pubsub ctx)] (pubsub/stop ps))
  (when-let [c (:cache ctx)] (kv/stop c))
  (when-let [store (:event-store ctx)] (es/stop store))
  (when-let [dir (::cache-dir ctx)]
    (let [f (java.io.File. dir)]
      (when (.exists f) (doseq [c (.listFiles f)] (.delete c)) (.delete f)))))

(defmacro with-test-ctx [[sym] & body]
  `(let [~sym (create-context)] (try ~@body (finally (stop-context ~sym)))))

(defn- settle-until! [pred & {:keys [timeout-ms] :or {timeout-ms 4000}}]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (cond (pred) true
            (> (System/currentTimeMillis) deadline) false
            :else (do (Thread/sleep 25) (recur))))))

;; Two shapes. Each carries distinctive SOURCE text (RR-6's re-emission
;; authority) that differs from `pr-str` of the decoded tree, so a pattern
;; offered from the decoded structure rather than the exact source is visible.
(def ^:private tree-a
  [:sequence
   [:llm {:reads [:doc] :writes [:summary]}]
   [:final {:keys [:summary]}]])
(def ^:private source-a
  "[:sequence\n  [:llm {:reads [:doc] :writes [:summary]}]\n  [:final {:keys [:summary]}]]")
(def ^:private tree-b
  [:sequence
   [:llm {:reads [:doc] :writes [:notes]}]
   [:llm {:reads [:notes] :writes [:summary]}]
   [:final {:keys [:summary]}]])
(def ^:private source-b
  "[:sequence\n  [:llm {:reads [:doc] :writes [:notes]}]\n  [:llm {:reads [:notes] :writes [:summary]}]\n  [:final {:keys [:summary]}]]")

(defn- classify! [ctx source-sheet-id source-tick-id class-id]
  (cp/process-command
   (assoc ctx :command
          {:command/name :ontology/assign-task-class
           :command/id (random-uuid)
           :command/timestamp (time/now)
           :source-sheet-id source-sheet-id
           :source-tick-id source-tick-id
           :source-node-id (random-uuid)
           :assigned-tree-id class-id
           :confidence 0.95
           :top-candidates []
           :reasoning "rr20"
           :was-fresh-mint? true})))

(defn- capture-floor! [ctx class-id signature]
  (cp/process-command
   (assoc ctx :command
          {:command/name :ontology/record-claim-deltas
           :command/id (random-uuid)
           :command/timestamp (time/now)
           :granularity :tree-class
           :target-identifier class-id
           :deltas [{:operation :add :kind :representative-use
                     :content signature :episodes []
                     :from-legacy-corpus false
                     :evidence-basis :classification-signature}]
           :evidence-event-count 0
           :claim-set-version (ontology/get-claim-set-version ctx :tree-class class-id)})))

(defn- emit! [ctx source-sheet-id source-tick-id tree source status]
  (cp/process-command
   (assoc ctx :command
          {:command/name :sheet/record-rlm-tree-execution-completion
           :command/id (random-uuid)
           :command/timestamp (time/now)
           :sheet-id (random-uuid)
           :tick-id (random-uuid)
           :trajectory []
           :total-usage {:total-tokens 0}
           :status status
           :duration-ms 1
           :tree-fingerprint (rlm-fingerprint/fingerprint tree)
           :generated-tree tree
           :generated-tree-source source
           :source-sheet-id source-sheet-id
           :source-tick-id source-tick-id})))

(defn- pattern-strengths [ctx class-id]
  (filterv :recommended-pattern
           (:strengths (ontology/get-description ctx :tree-class class-id))))

(defn- offered-patterns [ctx class-id]
  (into #{} (map :recommended-pattern) (pattern-strengths ctx class-id)))

(defn- offered-pattern-count-settles-to [ctx class-id n]
  (settle-until! #(= n (count (pattern-strengths ctx class-id)))))

(defn- new-campaign! [ctx class-id]
  (let [source-sheet-id (random-uuid) source-tick-id (random-uuid)]
    (classify! ctx source-sheet-id source-tick-id class-id)
    [source-sheet-id source-tick-id]))

(deftest a-failed-shape-is-recorded-as-failed-and-never-offered-as-the-worked-pattern
  (testing "a lone failing campaign leaves the class with no proven pattern to offer"
    (with-test-ctx [ctx]
      (let [class-id (random-uuid)
            [sheet tick] (new-campaign! ctx class-id)]
        (capture-floor! ctx class-id "implement: summarize a document")
        (Thread/sleep 200)
        (emit! ctx sheet tick tree-a source-a :failure)
        (Thread/sleep 800)
        (is (empty? (pattern-strengths ctx class-id))
            "a failed shape is not offered as a worked pattern")
        (is (nil? (:recommended-pattern
                   (harvest/harvest-body
                    (ontology/get-description ctx :tree-class class-id) 12)))
            "harvest offers nothing when nothing succeeded")
        (is (some #(and (= :weakness (:kind %))
                        (= source-a (:recommendation %)))
                  (ontology/get-claims ctx :tree-class class-id))
            "the failed shape is recorded as having failed, with its exact source")))))

(deftest a-repair-records-the-repair-as-proven-and-the-first-attempt-as-failed
  (testing "failure with shape A then success with shape B offers B, never A"
    (with-test-ctx [ctx]
      (let [class-id (random-uuid)
            [sheet tick] (new-campaign! ctx class-id)]
        (capture-floor! ctx class-id "implement: summarize a document")
        (Thread/sleep 200)
        (emit! ctx sheet tick tree-a source-a :failure)
        (emit! ctx sheet tick tree-b source-b :success)
        (is (offered-pattern-count-settles-to ctx class-id 1))
        (is (= #{source-b} (offered-patterns ctx class-id))
            "the repaired, successful shape is the class's worked pattern, as exact source")
        (is (= source-b
               (:recommended-pattern
                (harvest/harvest-body
                 (ontology/get-description ctx :tree-class class-id) 12)))
            "harvest ships the proven shape")
        (is (some #(and (= :weakness (:kind %)) (= source-a (:recommendation %)))
                  (ontology/get-claims ctx :tree-class class-id))
            "the first attempt is recorded as failed")))))

(deftest a-later-failure-never-displaces-an-earlier-proven-shape
  (testing "success with A, then a later campaign fails with B: A stays proven, B is recorded as failed"
    (with-test-ctx [ctx]
      (let [class-id (random-uuid)
            [sheet1 tick1] (new-campaign! ctx class-id)]
        (capture-floor! ctx class-id "implement: summarize a document")
        (Thread/sleep 200)
        (emit! ctx sheet1 tick1 tree-a source-a :success)
        (is (offered-pattern-count-settles-to ctx class-id 1))
        (let [[sheet2 tick2] (new-campaign! ctx class-id)]
          (emit! ctx sheet2 tick2 tree-b source-b :failure)
          (Thread/sleep 800)
          (is (= #{source-a} (offered-patterns ctx class-id))
              "the later failure neither displaces nor joins the proven pattern")
          (is (= source-a
                 (:recommended-pattern
                  (harvest/harvest-body
                   (ontology/get-description ctx :tree-class class-id) 12))))
          (is (some #(and (= :weakness (:kind %)) (= source-b (:recommendation %)))
                    (ontology/get-claims ctx :tree-class class-id))
              "the failed shape is recorded as failed"))))))

(deftest a-class-that-succeeds-with-two-shapes-keeps-both
  (testing "two successful shapes coexist; a re-success of one reinforces only its own claim"
    (with-test-ctx [ctx]
      (let [class-id (random-uuid)
            [sheet1 tick1] (new-campaign! ctx class-id)]
        (capture-floor! ctx class-id "implement: summarize a document")
        (Thread/sleep 200)
        (emit! ctx sheet1 tick1 tree-a source-a :success)
        (is (offered-pattern-count-settles-to ctx class-id 1))
        (let [[sheet2 tick2] (new-campaign! ctx class-id)]
          (emit! ctx sheet2 tick2 tree-b source-b :success)
          (is (offered-pattern-count-settles-to ctx class-id 2))
          (is (= #{source-a source-b} (offered-patterns ctx class-id))
              "both proven shapes are kept, distinguishable by their exact source")
          (let [support-before (into {} (map (juxt :recommendation :support))
                                     (filter :recommendation
                                             (ontology/get-claims ctx :tree-class class-id)))
                [sheet3 tick3] (new-campaign! ctx class-id)]
            (emit! ctx sheet3 tick3 tree-a source-a :success)
            (is (settle-until!
                 #(let [support-after (into {} (map (juxt :recommendation :support))
                                            (filter :recommendation
                                                    (ontology/get-claims ctx :tree-class class-id)))]
                    (and (< (get support-before source-a 0) (get support-after source-a 0))
                         (= (get support-before source-b) (get support-after source-b)))))
                "the repeated success reinforces shape A's claim only")
            (is (= 2 (count (pattern-strengths ctx class-id)))
                "still exactly two worked patterns — no duplicate, no displacement")))))))

(deftest a-pattern-is-offered-as-its-exact-recorded-source
  (testing "OfferedPatternsAreUsable: what is offered is the exact source text, not a re-printed structure"
    (with-test-ctx [ctx]
      (let [class-id (random-uuid)
            [sheet tick] (new-campaign! ctx class-id)]
        (capture-floor! ctx class-id "implement: summarize a document")
        (Thread/sleep 200)
        (emit! ctx sheet tick tree-a source-a :success)
        (is (offered-pattern-count-settles-to ctx class-id 1))
        (is (= source-a (:recommended-pattern (first (pattern-strengths ctx class-id)))))
        (is (not (str/includes? (:recommended-pattern (first (pattern-strengths ctx class-id)))
                                (pr-str tree-a)))
            "the pr-str re-rendering is not what is offered")))))
