(ns ai.obney.orc.orc-service.cc6-cv2-claim-enrichment-test
  "CC-6 — CV-2's worked-DSL enrichment writes CLAIM OPERATIONS.

   CV-2 (ADR 0017 decision 3) lands the tree an RLM actually emitted as the
   class's `:recommended-pattern`, which is the content EL-4 harvest ships in a
   harvested specialist. It did that by re-recording the whole description body
   on every emit.

   THE RATCHET THIS REMOVES. The forensic behind ADR 0021 counted 145 rejected
   consolidations; 14 of them were the anti-recency valve refusing a body
   because CV-2's mechanical `:trait` string was not reproduced verbatim by the
   reflection LLM. The system was demanding that a model re-type a string the
   system itself had written. That only disappears if CV-2 stops writing bodies
   and starts moving a COUNTER: a re-emit of the same pattern REINFORCES the
   claim already there, and a changed pattern EDITS it, so the claim keeps its
   identity and its accumulated support and nothing has to be re-typed to
   survive.

   Real grain: the real completion command, the real registered enrichment
   processor, assertions read the claim set and the assembled body back."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [dscloj.core :as dscloj]
            [ai.obney.orc.orc-service.interface.schemas]
            [ai.obney.orc.orc-service.core.commands]
            [ai.obney.orc.orc-service.core.read-models]
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

;; Harness hygiene only: a threshold-fired autonomous consolidation must never
;; reach a real provider. CV-2's enrichment itself uses NO LLM.
(defn- stub-predict-fixture [f]
  (with-redefs [dscloj/predict
                (fn [_provider _module _inputs _options]
                  {:outputs {:operations []}
                   :usage {:total-tokens 1}
                   :model "stub"})]
    (f)))

(use-fixtures :each stub-predict-fixture)

(defn- create-context []
  (let [ps (pubsub/start {:type :core-async :topic-fn :event/type})
        event-store (es/start {:conn {:type :in-memory} :event-pubsub ps :logger nil})
        cache-dir (str "/tmp/cc6-cv2-" (random-uuid))
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
  (when-let [es (:event-store ctx)] (es/stop es))
  (when-let [dir (::cache-dir ctx)]
    (let [f (java.io.File. dir)]
      (when (.exists f) (doseq [c (.listFiles f)] (.delete c)) (.delete f)))))

(defmacro with-test-ctx [[sym] & body]
  `(let [~sym (create-context)] (try ~@body (finally (stop-context ~sym)))))

(def ^:private emitted-tree
  [:sequence
   [:llm {:reads [:doc] :writes [:summary]}]
   [:final {:keys [:summary]}]])

(def ^:private revised-tree
  [:sequence
   [:llm {:reads [:doc] :writes [:notes]}]
   [:llm {:reads [:notes] :writes [:summary]}]
   [:final {:keys [:summary]}]])

(defn- classify! [ctx source-sheet-id class-id]
  (cp/process-command
    (assoc ctx :command
           {:command/name :ontology/assign-task-class
            :command/id (random-uuid)
            :command/timestamp (time/now)
            :source-sheet-id source-sheet-id
            :source-tick-id (random-uuid)
            :source-node-id (random-uuid)
            :assigned-tree-id class-id
            :confidence 0.95
            :top-candidates []
            :reasoning "test"
            :was-fresh-mint? true})))

(defn- capture-floor!
  "CV-1's floor as CC-6 writes it — a `:representative-use` claim carrying the
   task signature, admitted on its declared classification-signature basis."
  [ctx class-id signature]
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

(defn- complete-emit! [ctx source-sheet-id tree]
  (cp/process-command
    (assoc ctx :command
           (cond-> {:command/name :sheet/record-rlm-tree-execution-completion
                    :command/id (random-uuid)
                    :command/timestamp (time/now)
                    :sheet-id (random-uuid)   ;; ephemeral Phase-2 sheet
                    :tick-id (random-uuid)
                    :trajectory []
                    :total-usage {:total-tokens 0}}
             source-sheet-id (assoc :source-sheet-id source-sheet-id)
             tree            (assoc :generated-tree tree)))))

(defn- pattern-claims
  "Every claim carrying a worked-DSL recommendation."
  [ctx class-id]
  (filterv #(and (= :strength (:kind %)) (some? (:recommendation %)))
           (ontology/get-claims ctx :tree-class class-id)))

(defn- body-pattern-strengths [ctx class-id]
  (filterv :recommended-pattern
           (:strengths (ontology/get-description ctx :tree-class class-id))))

(defn- tree-class-body-writes [ctx class-id]
  (->> (es/read (:event-store ctx)
                {:tenant-id (:tenant-id ctx)
                 :types #{:ontology/tree-description-updated}})
       (into [])
       (filterv #(and (= :tree-class (:target-type %))
                      (= class-id (:target-id %))))))

;; ===========================================================================
;; CYCLE 4 — the emitted DSL lands as a CLAIM, and reaches the assembled body
;; and harvest exactly as before.
;; ===========================================================================

(deftest emitted-dsl-lands-as-a-strength-claim
  (testing "after an emit the class gains a :strength claim whose
            :recommendation is the emitted worked-DSL; the assembled body
            surfaces it as :recommended-pattern; harvest still ships it; and NO
            whole-body tree-class write happened"
    (with-test-ctx [ctx]
      (let [source-sheet-id (random-uuid)
            class-id (random-uuid)
            signature "implement: summarize a document"]
        (classify! ctx source-sheet-id class-id)
        (capture-floor! ctx class-id signature)
        (Thread/sleep 200)
        (complete-emit! ctx source-sheet-id emitted-tree)
        (Thread/sleep 600)
        (let [pcs (pattern-claims ctx class-id)
              body (ontology/get-description ctx :tree-class class-id)]
          (is (= 1 (count pcs)) "exactly one worked-pattern claim")
          (is (= (pr-str emitted-tree) (:recommendation (first pcs)))
              "carrying the emitted DSL")
          (is (false? (:legacy-provenance (first pcs)))
              "declared as an emitted artifact, NOT as the legacy corpus")
          (is (= 1 (count (body-pattern-strengths ctx class-id)))
              "and the assembled body surfaces it as a :strengths entry")
          (is (= (pr-str emitted-tree)
                 (:recommended-pattern (first (body-pattern-strengths ctx class-id))))
              "with :recommended-pattern = the emitted DSL")
          (is (= (pr-str emitted-tree)
                 (:recommended-pattern (harvest/harvest-body body 12)))
              "harvest still ships the real worked pattern")
          (is (some? (some #(clojure.string/includes? % signature)
                           (:representative-uses body)))
              "CV-1's signature survives the enrichment — it is a separate claim
               now, not a body field a second writer can overwrite"))
        (is (empty? (tree-class-body-writes ctx class-id))
            "no whole-body :tree-class write on either path")))))

;; ===========================================================================
;; CYCLE 5 — an identical re-emit REINFORCES, it does not duplicate.
;; This is the 14-of-145 ratchet turning into a counter.
;; ===========================================================================

(deftest an-identical-re-emit-reinforces-the-existing-claim
  (testing "re-emitting the SAME DSL leaves exactly one worked-pattern claim and
            raises its support — corroboration, not a duplicate entry"
    (with-test-ctx [ctx]
      (let [source-sheet-id (random-uuid)
            class-id (random-uuid)]
        (classify! ctx source-sheet-id class-id)
        (capture-floor! ctx class-id "implement: summarize a document")
        (Thread/sleep 200)
        (complete-emit! ctx source-sheet-id emitted-tree)
        (Thread/sleep 600)
        (let [before (first (pattern-claims ctx class-id))]
          (complete-emit! ctx source-sheet-id emitted-tree)
          (Thread/sleep 600)
          (let [after (pattern-claims ctx class-id)]
            (is (= 1 (count after))
                "still exactly ONE worked-pattern claim — no duplicate")
            (is (= (:claim-id before) (:claim-id (first after)))
                "and it is the SAME claim, keeping its identity")
            (is (< (:support before) (:support (first after)))
                "the re-emit REINFORCED it: the mechanical entry is a counter now")))))))

;; ===========================================================================
;; CYCLE 6 — a CHANGED pattern EDITS the same claim rather than adding a rival.
;; ===========================================================================

(deftest a-revised-emit-edits-the-same-claim
  (testing "emitting a DIFFERENT tree rewords the existing worked-pattern claim
            in place — same claim-id, accumulated support kept, new DSL — rather
            than leaving two rival patterns for harvest to choose between"
    (with-test-ctx [ctx]
      (let [source-sheet-id (random-uuid)
            class-id (random-uuid)]
        (classify! ctx source-sheet-id class-id)
        (capture-floor! ctx class-id "implement: summarize a document")
        (Thread/sleep 200)
        (complete-emit! ctx source-sheet-id emitted-tree)
        (Thread/sleep 600)
        (let [before (first (pattern-claims ctx class-id))]
          (complete-emit! ctx source-sheet-id revised-tree)
          (Thread/sleep 600)
          (let [after (pattern-claims ctx class-id)]
            (is (= 1 (count after)) "still one worked-pattern claim, not two rivals")
            (is (= (:claim-id before) (:claim-id (first after)))
                "the claim kept its identity across the revision")
            (is (= (pr-str revised-tree) (:recommendation (first after)))
                "and now carries the NEW emitted DSL")
            (is (<= (:support before) (:support (first after)))
                "an edit reinforces as well as rewords — nothing is lost")))))))

;; ===========================================================================
;; CYCLE 7 — the legacy-body hazard. A target that still holds only a
;; pre-claim body is NOT a place for a mechanical claim to land.
;; ===========================================================================

(deftest enrichment-refuses-to-collapse-a-legacy-bodied-class
  (testing "a class whose knowledge is still a legacy whole-body description and
            which has NO claims yet is left alone: writing one mechanical claim
            would make CC-3 re-derive :current from that single claim and erase
            a lifetime of consolidations — the context collapse ADR 0021 exists
            to make unrepresentable, arriving through a side door"
    (with-test-ctx [ctx]
      (let [source-sheet-id (random-uuid)
            class-id (random-uuid)]
        (classify! ctx source-sheet-id class-id)
        ;; A seeded / pre-claim class: whole body, no claims.
        (cp/process-command
          (assoc ctx :command
                 {:command/name :ontology/record-tree-class-description
                  :command/id (random-uuid)
                  :command/timestamp (time/now)
                  :target-id class-id
                  :body {:summary "a long-consolidated class"
                         :capabilities ["extracts structured fields"
                                        "handles multi-page inputs"]
                         :strengths [] :weaknesses []
                         :representative-uses ["invoice extraction"]
                         :avoid-when ["free-form prose"]
                         :version 9
                         :consolidated-from-event-count 120}}))
        (Thread/sleep 200)
        (complete-emit! ctx source-sheet-id emitted-tree)
        (Thread/sleep 600)
        (let [body (ontology/get-description ctx :tree-class class-id)]
          (is (empty? (ontology/get-claims ctx :tree-class class-id))
              "no claim was written onto a legacy-bodied target")
          (is (= ["extracts structured fields" "handles multi-page inputs"]
                 (vec (:capabilities body)))
              "and the accumulated body is intact — nothing was collapsed"))))))
