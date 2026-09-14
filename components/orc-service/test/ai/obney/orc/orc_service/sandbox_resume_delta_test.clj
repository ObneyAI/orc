(ns ai.obney.orc.orc-service.sandbox-resume-delta-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [ai.obney.orc.llm.interface :as llm]
            [ai.obney.orc.orc-service.core.read-models :as rm]
            [ai.obney.orc.orc-service.core.researcher-resume-state :as resume]
            [ai.obney.orc.orc-service.interface :as sheet]
            [ai.obney.orc.orc-service.interface.schemas :as schemas]
            [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.event-store-sqlite-v3.interface]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp]
            [ai.obney.grain.time.interface :as time]
            [malli.core :as m])
  (:import (java.nio.charset StandardCharsets)))

(defn- resume-state [revision sandbox-vars]
  {:version 2
   :revision revision
   :ownership-epoch 1
   :next-iteration revision
   :sandbox-vars sandbox-vars
   :var-creation-times (zipmap (keys sandbox-vars) (repeat 0))
   :usage {:prompt-tokens revision
           :completion-tokens 0
           :total-tokens revision}
   :cumulative-tree-ms revision
   :iteration-attempts {}
   :campaign-started-at-ms 100
   :campaign-deadline-ms 10000})

(defn- schema-declares-key? [schema key]
  (some (fn [form]
          (and (vector? form)
               (= key (first form))))
        (tree-seq coll? seq (m/form schema))))

(deftest rr18-classifier-payload-is-an-explicit-non-sandbox-resume-field
  (testing "full snapshots and sandbox deltas carry one campaign's exact classifier context"
    (let [classification-context
          {:tree-id (random-uuid)
           :r05-classifier
           {:structural {:assigned-tree-id (random-uuid)
                         :confidence 0.91
                         :was-fresh-mint? false
                         :reasoning "first and only campaign classification"
                         :top-candidates []
                         :rerank-fallback? false}
            :behavioral {:behaviors []
                         :rerank-fallback? false}}}
          before (assoc (resume-state 1 {:memo "before"})
                        :classification-context classification-context)
          after (assoc (resume-state 2 {:memo "after"})
                       :classification-context classification-context)
          full (resume/encode before nil nil 3)
          delta (resume/encode after full before 3)
          hydrated (resume/hydrate-latest [full delta])]
      (is (schema-declares-key? schemas/researcher-resume-state-v2
                                :classification-context)
          "the public V2 continuation contract names the carried classifier payload")
      (is (schema-declares-key? schemas/researcher-resume-state-v3
                                :classification-context)
          "the durable V3 fact contract names the carried classifier payload")
      (is (m/validate schemas/researcher-resume-state-v2 before))
      (is (m/validate schemas/researcher-resume-state-v3 full))
      (is (m/validate schemas/researcher-resume-state-v3 delta))
      (is (= classification-context (:classification-context full)))
      (is (= classification-context (:classification-context delta)))
      (is (= after hydrated))
      (is (= (resume/sandbox-hash (:sandbox-vars after))
             (:resulting-state-hash delta))
          "classifier context remains outside the sandbox delta hash"))))

(defn- checkpoint-command [sheet-id tick-id node-id state]
  {:command/id (random-uuid)
   :command/timestamp (time/now)
   :command/name :sheet/checkpoint-researcher-iteration
   :sheet-id sheet-id
   :tick-id tick-id
   :node-id node-id
   :resume-state state
   :iteration-record {:iteration-index (dec (:revision state))
                      :attempt-ordinal 0
                      :status :success}
   :resume? false
   :inputs {}})

(defn- legacy-checkpoint-command [sheet-id tick-id node-id checkpoint]
  {:command/id (random-uuid)
   :command/timestamp (time/now)
   :command/name :sheet/checkpoint-researcher-iteration
   :sheet-id sheet-id
   :tick-id tick-id
   :node-id node-id
   :checkpoint checkpoint
   :resume? false
   :inputs {}})

(defn- resume-events [ctx tick-id node-id]
  (->> (es/read (:event-store ctx)
                {:tenant-id (:tenant-id ctx)
                 :types #{:rlm/researcher-resume-state-saved}
                 :tags #{[:tick tick-id] [:node node-id]}})
       (into [])))

(defn- append-resume-facts! [ctx sheet-id tick-id node-id facts]
  (es/append
   (:event-store ctx)
   {:tenant-id (:tenant-id ctx)
    :events
    (mapv (fn [fact]
            (es/->event
             {:type :rlm/researcher-resume-state-saved
              :tags #{[:sheet sheet-id] [:tick tick-id] [:node node-id]}
              :body {:sheet-id sheet-id
                     :tick-id tick-id
                     :node-id node-id
                     :revision (:revision fact)
                     :next-iteration (:next-iteration fact)
                     :resume-state fact
                     :yielded? false
                     :saved-at "2026-09-08T00:00:00Z"}}))
          facts)}))

(deftest det-e2e-276-first-resume-fact-is-a-hydrated-full-snapshot
  (testing "the durable encoding advances while the public read stays V2-shaped"
    (h/with-async-test-context [ctx]
      (let [sheet-id (random-uuid)
            tick-id (random-uuid)
            node-id (random-uuid)
            submitted (resume-state 1 {:memo "kept" :count 1})]
        (h/run-and-apply! ctx
                          (checkpoint-command sheet-id tick-id node-id submitted))
        (let [events (resume-events ctx tick-id node-id)
              event (first events)
              projected (:resume-state
                         (rm/get-researcher-resume-state
                          ctx sheet-id tick-id node-id))]
          (is (= 1 (count events)))
          (is (= 3 (get-in event [:resume-state :version])))
          (is (= :full-snapshot
                 (get-in event [:resume-state :sandbox-fact-kind])))
          (is (= (:sandbox-vars submitted)
                 (get-in event [:resume-state :sandbox-snapshot])))
          (is (= submitted projected)))))))

(deftest det-e2e-276-explicit-interval-bounds-deltas-between-full-snapshots
  (testing "cadence follows durable revision and every read is fully hydrated"
    (h/with-async-test-context [ctx]
      (let [sheet-id (random-uuid)
            tick-id (random-uuid)
            node-id (random-uuid)
            states [(resume-state 1 {:memo "one" :stable 7})
                    (resume-state 2 {:memo "two" :stable 7 :added true})
                    (resume-state 3 {:memo "three" :stable 7 :added true})]
            projected
            (mapv (fn [state]
                    (h/run-and-apply!
                     ctx
                     (assoc (checkpoint-command sheet-id tick-id node-id state)
                            :sandbox-snapshot-interval 3))
                    (:resume-state
                     (rm/get-researcher-resume-state
                      ctx sheet-id tick-id node-id)))
                  states)
            facts (mapv :resume-state (resume-events ctx tick-id node-id))]
        (is (= states projected))
        (is (= [:full-snapshot :sandbox-delta :full-snapshot]
               (mapv :sandbox-fact-kind facts)))
        (is (= {:memo "two" :added true} (:sandbox-puts (second facts))))
        (is (= 1 (:predecessor-revision (second facts))))
        (is (= (:resulting-state-hash (first facts))
               (:predecessor-state-hash (second facts))))))))

(deftest det-e2e-276-delta-deletes-removed-sandbox-keys
  (testing "a removed key is durable evidence and cannot survive hydration"
    (h/with-async-test-context [ctx]
      (let [sheet-id (random-uuid)
            tick-id (random-uuid)
            node-id (random-uuid)
            before (resume-state 1 {:keep "yes" :remove "gone"})
            after (resume-state 2 {:keep "yes"})]
        (doseq [state [before after]]
          (h/run-and-apply!
           ctx
           (assoc (checkpoint-command sheet-id tick-id node-id state)
                  :sandbox-snapshot-interval 3)))
        (let [delta (:resume-state
                     (second (resume-events ctx tick-id node-id)))
              projected (:resume-state
                         (rm/get-researcher-resume-state
                          ctx sheet-id tick-id node-id))]
          (is (= #{:remove} (:sandbox-deletes delta)))
          (is (= after projected)))))))

(deftest det-e2e-276-corrupt-or-incomplete-delta-chains-fail-loudly
  (testing "the public projection never exposes unverifiable sandbox state"
    (let [before (resume-state 1 {:key "before"})
          after (resume-state 2 {:key "after"})
          full (resume/encode before nil nil 3)
          delta (resume/encode after full before 3)
          cases [{:label :missing-predecessor
                  :facts [delta]}
                 {:label :predecessor-hash-mismatch
                  :facts [full (assoc delta :predecessor-state-hash
                                      "sha256:wrong")]}
                 {:label :payload-mutation
                  :facts [full (assoc-in delta [:sandbox-puts :key]
                                         "tampered")]}
                 {:label :resulting-hash-mismatch
                  :facts [full (assoc delta :resulting-state-hash
                                      "sha256:wrong")]}]]
      (doseq [{:keys [label facts]} cases]
        (h/with-async-test-context [ctx]
          (let [sheet-id (random-uuid)
                tick-id (random-uuid)
                node-id (random-uuid)]
            (append-resume-facts! ctx sheet-id tick-id node-id facts)
            (is (thrown-with-msg?
                 clojure.lang.ExceptionInfo
                 #"researcher sandbox"
                 (rm/get-researcher-resume-state
                  ctx sheet-id tick-id node-id))
                (name label))))))))

(deftest det-e2e-276-full-and-delta-facts-enforce-their-conditional-shapes
  (testing "durable facts with absent or forbidden kind-specific fields fail loudly"
    (let [state (resume-state 1 {:key "value"})
          next-state (resume-state 2 {:key "value"})
          full (resume/encode state nil nil 3)
          delta (resume/encode next-state full state 3)
          cases [{:label :full-missing-snapshot
                  :facts [(-> full
                              (dissoc :sandbox-snapshot)
                              (assoc :resulting-state-hash
                                     (resume/sandbox-hash nil)))]}
                 {:label :full-with-delta-field
                  :facts [(assoc full :sandbox-puts {})]}
                 {:label :delta-missing-puts
                  :facts [full (dissoc delta :sandbox-puts)]}
                 {:label :delta-with-snapshot
                 :facts [full (assoc delta :sandbox-snapshot {})]}]]
      (doseq [{:keys [label facts]} cases]
        (let [sheet-id (random-uuid)
              tick-id (random-uuid)
              node-id (random-uuid)
              malformed (last facts)
              event-body {:sheet-id sheet-id
                          :tick-id tick-id
                          :node-id node-id
                          :revision (:revision malformed)
                          :next-iteration (:next-iteration malformed)
                          :resume-state malformed
                          :yielded? false
                          :saved-at "2026-09-08T00:00:00Z"}]
          (is (not (m/validate
                    (schemas/events :rlm/researcher-resume-state-saved)
                    event-body))
              (str (name label) " passed the event schema"))
          (is (thrown-with-msg?
               clojure.lang.ExceptionInfo
               #"researcher sandbox resume fact shape"
               (resume/hydrate-latest facts))
              (name label)))))))

(deftest det-e2e-276-legacy-checkpoints-remain-readable-across-the-v3-transition
  (testing "V1 stays on its compatibility projection and V2 is re-anchored by V3"
    (h/with-async-test-context [ctx]
      (let [sheet-id (random-uuid)
            legacy-tick-id (random-uuid)
            transition-tick-id (random-uuid)
            node-id (random-uuid)
            checkpoint {:version 1
                        :revision 1
                        :next-iteration 1
                        :sandbox-vars {:legacy true}}
            before (resume-state 1 {:value "v2"})
            after (resume-state 2 {:value "v3"})]
        (h/run-and-apply!
         ctx
         (legacy-checkpoint-command
          sheet-id legacy-tick-id node-id checkpoint))
        (append-resume-facts!
         ctx sheet-id transition-tick-id node-id [before])
        (h/run-and-apply!
         ctx
         (assoc (checkpoint-command sheet-id transition-tick-id node-id after)
                :sandbox-snapshot-interval 3))
        (let [facts (mapv :resume-state
                          (resume-events ctx transition-tick-id node-id))]
          (is (= checkpoint
                 (:checkpoint
                  (rm/get-researcher-checkpoint
                   ctx sheet-id legacy-tick-id node-id))))
          (is (= [2 3] (mapv :version facts)))
          (is (= :full-snapshot (:sandbox-fact-kind (second facts))))
          (is (= after
                 (:resume-state
                  (rm/get-researcher-resume-state
                   ctx sheet-id transition-tick-id node-id)))))))))

(defn- execute-two-revision-campaign [ctx name rlm-config]
  (let [calls (atom 0)
        definition
        (sheet/workflow name
          (sheet/blackboard {:summary :string})
          (sheet/repl-researcher "researcher"
            :instruction "persist one sandbox value, then finish"
            :writes [:summary]
            :max-iterations 2
            :rlm (merge {:checkpointed? true
                         :recursive? false
                         :quantum {:max-iterations 1}
                         :timeouts {:provider-ms 1000
                                    :iteration-ms 5000
                                    :campaign-ms 15000}}
                        rlm-config)))
        sheet-id (sheet/build-workflow! ctx definition)
        researcher-id
        (:id (first (filter #(= :repl-researcher (:type %))
                            (sheet/get-nodes-for-sheet ctx sheet-id))))]
    (with-redefs [llm/predict
                  (fn [& _]
                    (let [call (swap! calls inc)]
                      {:outputs
                       {:code (if (= 1 call)
                                "(store! :memo \"changed\")"
                                "(final! {:summary (get-var :memo)})")}
                       :reasoning "deterministic snapshot-cadence proof"
                       :usage {:prompt_tokens 1
                               :completion_tokens 1
                               :total_tokens 2}}))]
      (let [result (sheet/execute ctx sheet-id {} :timeout-ms 15000)]
        {:result result
         :facts (mapv :resume-state
                      (resume-events ctx (:trace-id result) researcher-id))}))))

(deftest det-e2e-276-node-configuration-controls-cadence-with-a-safe-default
  (testing "node RLM configuration reaches persistence and absence defaults to one"
    (h/with-async-test-context [explicit-ctx {:context {:llm-provider :test}}]
      (h/with-async-test-context [default-ctx {:context {:llm-provider :test}}]
        (let [explicit (execute-two-revision-campaign
                        explicit-ctx "rr14-explicit-cadence"
                        {:sandbox-snapshot-interval 3})
              defaulted (execute-two-revision-campaign
                         default-ctx "rr14-default-cadence" {})]
          (is (= :success (get-in explicit [:result :status])))
          (is (= :success (get-in defaulted [:result :status])))
          (is (= [:full-snapshot :sandbox-delta]
                 (mapv :sandbox-fact-kind (:facts explicit))))
          (is (= [:full-snapshot :full-snapshot]
                 (mapv :sandbox-fact-kind (:facts defaulted)))))))))

(deftest det-e2e-276-snapshot-interval-must-be-positive
  (let [schema (schemas/commands :sheet/set-repl-researcher-config)
        base {:sheet-id (random-uuid)
              :node-id (random-uuid)
              :instruction "validate cadence"
              :reads []
              :writes []
              :mcp-tools []
              :rlm {:checkpointed? true}}]
    (is (m/validate schema (assoc-in base [:rlm :sandbox-snapshot-interval] 1)))
    (is (not (m/validate schema
                         (assoc-in base [:rlm :sandbox-snapshot-interval] 0))))
    (is (not (m/validate schema
                         (assoc-in base [:rlm :sandbox-snapshot-interval] -1))))))

(defn- canonical-edn-bytes [values]
  (reduce +
          (map #(alength (.getBytes (pr-str %) StandardCharsets/UTF_8))
               values)))

(deftest det-e2e-276-deltas-reduce-large-working-set-event-bytes
  (testing "a one-value change is smaller than repeating the complete sandbox"
    (h/with-async-test-context [ctx]
      (let [sheet-id (random-uuid)
            tick-id (random-uuid)
            node-id (random-uuid)
            large-sandbox
            (into {}
                  (map (fn [index]
                         [(keyword (str "value-" index))
                          (apply str (repeat 256 (char (+ 65 (mod index 26)))))]))
                  (range 128))
            before (resume-state 1 large-sandbox)
            after (resume-state 2 (assoc large-sandbox :value-64 "changed"))]
        (doseq [state [before after]]
          (h/run-and-apply!
           ctx
           (assoc (checkpoint-command sheet-id tick-id node-id state)
                  :sandbox-snapshot-interval 3)))
        (let [delta-facts (mapv :resume-state
                                (resume-events ctx tick-id node-id))
              full-facts [(resume/encode-full before)
                          (resume/encode-full after)]
              delta-by (canonical-edn-bytes delta-facts)
              full-bytes (canonical-edn-bytes full-facts)]
          (is (= [:full-snapshot :sandbox-delta]
                 (mapv :sandbox-fact-kind delta-facts)))
          (is (< delta-by full-bytes)
              (pr-str {:fixture-values 128
                       :bytes-per-value 256
                       :delta-bytes delta-by
                       :full-snapshot-bytes full-bytes})))))))

(deftest det-e2e-276-sqlite-restart-reconstructs-the-exact-sandbox
  (testing "a cold projection verifies and hydrates a serialized delta chain"
    (let [db-file (str "/tmp/rr14-sandbox-delta-" (random-uuid) ".db")
          first-context (atom nil)
          reopened-context (atom nil)
          sheet-id (random-uuid)
          tick-id (random-uuid)
          node-id (random-uuid)
          before (resume-state 1 {:memo "before" :delete-me true})
          expected (resume-state 2 {:memo "after" :added [1 2 3]})]
      (try
        (let [ctx (h/create-async-test-context
                   {:event-store-conn {:type :sqlite
                                       :database-file db-file
                                       :maximum-pool-size 2}})]
          (reset! first-context ctx)
          (doseq [state [before expected]]
            (h/run-and-apply!
             ctx
             (assoc (checkpoint-command sheet-id tick-id node-id state)
                    :sandbox-snapshot-interval 3)))
          (is (= [:full-snapshot :sandbox-delta]
                 (mapv (comp :sandbox-fact-kind :resume-state)
                       (resume-events ctx tick-id node-id))))
          (h/stop-async-context ctx)
          (reset! first-context nil))
        (let [ctx (h/create-async-test-context
                   {:event-store-conn {:type :sqlite
                                       :database-file db-file
                                       :maximum-pool-size 2}})]
          (reset! reopened-context ctx)
          (is (= expected
                 (:resume-state
                  (rm/get-researcher-resume-state
                   ctx sheet-id tick-id node-id)))))
        (finally
          (when-let [ctx @reopened-context]
            (h/stop-async-context ctx))
          (when-let [ctx @first-context]
            (h/stop-async-context ctx))
          (doseq [suffix ["" "-wal" "-shm"]]
            (io/delete-file (str db-file suffix) true)))))))

(deftest det-e2e-276-cold-resume-applies-at-most-k-minus-one-deltas
  (testing "the latest verified full snapshot bounds hydration work"
    (h/with-async-test-context [ctx]
      (let [sheet-id (random-uuid)
            tick-id (random-uuid)
            node-id (random-uuid)
            states (mapv #(resume-state % {:revision % :stable "value"})
                         (range 1 6))]
        (doseq [state states]
          (h/run-and-apply!
           ctx
           (assoc (checkpoint-command sheet-id tick-id node-id state)
                  :sandbox-snapshot-interval 3)))
        (rmp/l1-clear!)
        (let [hydrate resume/hydrate
              hydrated-facts (atom 0)
              projected
              (with-redefs [resume/hydrate
                            (fn [prior fact]
                              (swap! hydrated-facts inc)
                              (hydrate prior fact))]
                (:resume-state
                 (rm/get-researcher-resume-state
                  ctx sheet-id tick-id node-id)))]
          (is (= (last states) projected))
          (is (<= @hydrated-facts 3)
              (str "interval 3 hydrated " @hydrated-facts
                   " facts instead of the latest snapshot plus two deltas")))))))
