(ns ai.obney.orc.orc-service.rr24-mint-iteration-provenance-test
  "RR-24 — contract tests: a behaviour minted by a researcher campaign records the
   iteration and attempt that minted it; provenance distinguishes a first-attempt
   mint from a late fallback; a replayed iteration neither re-mints nor forces a
   second reindex; mint identity stays stable. Propagated structural obligations
   `entity-fields.EffectClaim` and `entity-fields.CampaignIteration` are checked at
   their schema seams (expected already green — a finding, kept as guards).

   Contract tests are never weakened to pass. Seams: the real sandbox
   `mint-behavior!` under the landed RR-7 durable-effect options, the real
   `:ontology/mint-behavioral-subtree` command, the registered forced-reindex
   processor with `colbert-ops/create-index!` stubbed exactly as
   `deterministic_ontology_e2e_test` does (the colbert interface namespace must be
   loaded or the reindex dispatch skips itself)."
  (:require [clojure.test :refer [deftest is testing]]
            [malli.core :as m]
            [ai.obney.orc.orc-service.core.rlm-sandbox :as rlm-sandbox]
            [ai.obney.orc.orc-service.interface.schemas :as orc-schemas]
            [ai.obney.orc.orc-service.core.commands]
            [ai.obney.orc.ontology.interface :as ontology]
            [ai.obney.orc.ontology.interface.schemas]
            [ai.obney.orc.ontology.core.commands]
            [ai.obney.orc.ontology.core.read-models]
            [ai.obney.orc.ontology.core.todo-processors]
            [ai.obney.orc.colbert.interface]
            [ai.obney.orc.colbert.interface.schemas]
            [ai.obney.orc.colbert.core.operations :as colbert-ops]
            [ai.obney.grain.schema-util.interface :as schema-util]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.query-processor.interface :as qp]
            [ai.obney.grain.pubsub.interface :as pubsub]
            [ai.obney.grain.todo-processor-v2.interface :as tp]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]))

(defn- create-ctx [processor-names]
  (let [ps (pubsub/start {:type :core-async :topic-fn :event/type})
        event-store (es/start {:conn {:type :in-memory} :event-pubsub ps :logger nil})
        cache-dir (str "/tmp/rr24-" (random-uuid))
        base {:event-store event-store
              :cache (kv/start (lmdb/->KV-Store-LMDB {:storage-dir cache-dir :db-name "test"}))
              :tenant-id (random-uuid) :event-pubsub ps
              :command-registry (cp/global-command-registry)
              :query-registry (qp/global-query-registry)
              ::cache-dir cache-dir}
        processors (reduce-kv (fn [acc n {:keys [handler-fn topics]}]
                                (if (contains? processor-names n)
                                  (assoc acc n (tp/start {:event-pubsub ps :topics topics
                                                          :handler-fn handler-fn :context base}))
                                  acc))
                              {} @tp/processor-registry*)]
    (assoc base :processors processors)))

(defn- stop-ctx [ctx]
  (doseq [[_ p] (:processors ctx)] (tp/stop p))
  (pubsub/stop (:event-pubsub ctx)) (kv/stop (:cache ctx)) (es/stop (:event-store ctx))
  (let [f (java.io.File. (::cache-dir ctx))]
    (when (.exists f) (doseq [c (.listFiles f)] (.delete c)) (.delete f))))

(defmacro with-ctx [[sym procs] & body]
  `(let [~sym (create-ctx ~procs)] (try ~@body (finally (stop-ctx ~sym)))))

(defn- settle-until! [pred & {:keys [timeout-ms] :or {timeout-ms 4000}}]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop [] (cond (pred) true
                   (> (System/currentTimeMillis) deadline) false
                   :else (do (Thread/sleep 25) (recur))))))

(def ^:private body
  {:capabilities ["x"] :strengths [] :weaknesses [] :representative-uses ["x"] :avoid-when ["x"]
   :summary "Minted from a checkpointed campaign." :version 1 :consolidated-from-event-count 0})

(defn- mint-via-sandbox!
  "Run `(mint-behavior! name body)` inside a real sandbox built with the landed
   RR-7 durable-effect options. `claims` records every claim the sandbox makes."
  [ctx {:keys [sheet-id tick-id node-id iteration attempt epoch code-hash claims name]}]
  (let [rlm-ctx (rlm-sandbox/build-rlm-context
                 {:provider :openrouter :blackboard {} :declared-writes [:result]
                  :event-store (:event-store ctx) :tenant-id (:tenant-id ctx) :cache (:cache ctx)
                  :command-registry (:command-registry ctx)
                  :sheet-id sheet-id :tick-id tick-id :node-id node-id
                  :durable-source-required? true
                  :researcher-iteration iteration
                  :generated-code-hash code-hash
                  :researcher-ownership-epoch epoch
                  :effect-attempt-ordinal attempt
                  :claim-researcher-effect! (fn [claim] (swap! claims conj claim) {})
                  :complete-researcher-effect! (fn [_outcome] {})
                  :completed-researcher-effects (atom {})})
        code (str "(mint-behavior! " (pr-str name) " " (pr-str body) ")")]
    (rlm-sandbox/execute-rlm-code rlm-ctx code)))

(defn- provenance
  "RED-first seam: `ontology/behavior-mint-provenance` does not exist until RR-24 lands."
  [ctx target-id]
  (if-let [f (resolve 'ai.obney.orc.ontology.interface/behavior-mint-provenance)]
    (f ctx target-id)
    {:missing-fn 'ai.obney.orc.ontology.interface/behavior-mint-provenance}))

(defn- minted-events [ctx]
  (into [] (es/read (:event-store ctx) {:tenant-id (:tenant-id ctx) :types #{:ontology/behavioral-subtree-minted}})))

(deftest a-researcher-mint-records-its-iteration-and-attempt
  (testing "the minted event carries the iteration, the attempt ordinal and the ownership epoch that minted it"
    (with-ctx [ctx #{}]
      (let [claims (atom [])]
        (mint-via-sandbox! ctx {:sheet-id (random-uuid) :tick-id (random-uuid) :node-id (random-uuid)
                                :iteration 3 :attempt 2 :epoch 4 :code-hash "h1" :claims claims
                                :name "rr24-late-fallback"})
        (let [[event :as events] (minted-events ctx)]
          (is (= 1 (count events)))
          (is (= 3 (:researcher-iteration event)))
          (is (= 2 (:attempt-ordinal event)) "the attempt ordinal is explicit, not only hashed into the attempt identity")
          (is (= 4 (:ownership-epoch event)))
          (is (string? (:logical-action-identity event)))
          (is (string? (:attempt-identity event)))
          (is (= 1 (count @claims)) "claimed before the effect")
          (is (= [3 2 :behavior-mint] ((juxt :iteration-index :attempt-ordinal :kind) (first @claims)))))))))

(deftest provenance-distinguishes-a-first-attempt-mint-from-a-late-fallback
  (with-ctx [ctx #{}]
    (let [claims (atom [])]
      (mint-via-sandbox! ctx {:sheet-id (random-uuid) :tick-id (random-uuid) :node-id (random-uuid)
                              :iteration 0 :attempt 0 :epoch 1 :code-hash "h-first" :claims claims :name "rr24-first"})
      (mint-via-sandbox! ctx {:sheet-id (random-uuid) :tick-id (random-uuid) :node-id (random-uuid)
                              :iteration 5 :attempt 3 :epoch 2 :code-hash "h-late" :claims claims :name "rr24-fallback"})
      (let [by-name (into {} (map (juxt :name identity)) (minted-events ctx))
            prov (fn [n] (provenance ctx (:target-id (get by-name n))))]
        (is (= {:iteration-index 0 :attempt-ordinal 0 :ownership-epoch 1 :first-attempt? true}
               (prov "rr24-first")))
        (is (= {:iteration-index 5 :attempt-ordinal 3 :ownership-epoch 2 :first-attempt? false}
               (prov "rr24-fallback")))))))

(deftest a-replayed-iteration-neither-re-mints-nor-forces-a-second-reindex
  (testing "the same logical action minted twice (a replayed iteration) yields one minted event, one description, one derived id and ONE forced reindex"
    (with-ctx [ctx #{:ontology/on-behavioral-subtree-minted-force-rebuild}]
      (let [rebuilds (atom [])
            claims (atom [])
            sheet (random-uuid) tick (random-uuid) node (random-uuid)
            args {:sheet-id sheet :tick-id tick :node-id node :iteration 2 :attempt 0 :epoch 1
                  :code-hash "same-code" :claims claims :name "rr24-replayed"}]
        (with-redefs [colbert-ops/create-index! (fn [_ctx opts] (swap! rebuilds conj opts)
                                                  {:index-id (random-uuid) :index-path "/tmp/rr24-idx"
                                                   :num-passages 0 :duration-ms 1 :document-ids []
                                                   :document-count 0 :model-name "colbert-ir/colbertv2.0"
                                                   :index-name (:index-name opts)
                                                   :config {:split-documents? true :max-document-length 256 :use-faiss? false}})]
          (let [first-result (mint-via-sandbox! ctx args)
                _ (is (settle-until! #(= 1 (count @rebuilds))) "the first mint forces one rebuild")
                replay-result (mint-via-sandbox! ctx (assoc args :attempt 1 :epoch 2))]
            (Thread/sleep 400)
            (is (= 1 (count (minted-events ctx))) "a replayed iteration does not re-mint")
            (is (= 1 (count (into [] (es/read (:event-store ctx) {:tenant-id (:tenant-id ctx)
                                                                  :types #{:ontology/tree-description-updated}}))))
                "…and records no second description")
            (is (= 1 (count @rebuilds)) "…and forces no second reindex")
            (is (= (str (:target-id (first (minted-events ctx)))) (str (:raw-result first-result)) (str (:raw-result replay-result)))
                "both dispatches resolve to the one minted concept")))))))

(deftest mint-identity-remains-stable-across-attempts-and-replays
  (with-ctx [ctx #{}]
    (let [claims (atom [])
          parent (random-uuid)
          expected (java.util.UUID/nameUUIDFromBytes (.getBytes (str "mint:rr24-stable:" parent) "UTF-8"))]
      (doseq [[iteration attempt epoch] [[1 0 1] [1 1 1] [1 0 2]]]
        (let [rlm-ctx (rlm-sandbox/build-rlm-context
                       {:provider :openrouter :blackboard {} :declared-writes [:result]
                        :event-store (:event-store ctx) :tenant-id (:tenant-id ctx) :cache (:cache ctx)
                        :command-registry (:command-registry ctx)
                        :sheet-id (random-uuid) :tick-id (random-uuid) :node-id (random-uuid)
                        :durable-source-required? true :researcher-iteration iteration
                        :generated-code-hash "stable" :researcher-ownership-epoch epoch
                        :effect-attempt-ordinal attempt
                        :claim-researcher-effect! (fn [claim] (swap! claims conj claim) {})
                  :complete-researcher-effect! (fn [_outcome] {})
                        :completed-researcher-effects (atom {})})]
          (rlm-sandbox/execute-rlm-code rlm-ctx (str "(mint-behavior! \"rr24-stable\" " (pr-str body) " :parent " (pr-str (str parent)) ")"))))
      (is (every? #(= expected (:target-id %)) (minted-events ctx)) "every attempt resolves to the one derived concept id")
      (is (= 1 (count (distinct (map :target-id (minted-events ctx))))))
      (is (some? (ontology/get-description ctx :tree-fingerprint expected))))))

(deftest effect-claim-and-iteration-records-carry-their-declared-fields
  (testing "entity-fields.EffectClaim — the claim event schema requires every declared field"
    (let [schema (get @schema-util/registry* :rlm/researcher-effect-claimed)
          claim {:sheet-id (random-uuid) :tick-id (random-uuid) :node-id (random-uuid)
                 :iteration-index 0 :logical-action-identity "l" :attempt-identity "a"
                 :attempt-ordinal 0 :ownership-epoch 1 :kind :behavior-mint :status :claimed
                 :claimed-at "2026-09-11T00:00:00Z" :resolved-at nil}]
      (is (some? schema))
      (is (m/validate schema claim))
      (doseq [k [:iteration-index :logical-action-identity :attempt-identity :attempt-ordinal :ownership-epoch :kind]]
        (is (not (m/validate schema (dissoc claim k))) (str k " is required")))))
  (testing "entity-fields.CampaignIteration — the iteration record schema requires index, attempt and status"
    (let [rec {:iteration-index 0 :attempt-ordinal 0 :status :success}]
      (is (m/validate orc-schemas/researcher-iteration-record rec))
      (doseq [k [:iteration-index :attempt-ordinal :status]]
        (is (not (m/validate orc-schemas/researcher-iteration-record (dissoc rec k))) (str k " is required"))))))
