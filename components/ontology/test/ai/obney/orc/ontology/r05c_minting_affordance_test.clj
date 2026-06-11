(ns ai.obney.orc.ontology.r05c-minting-affordance-test
  "R05c — Minting affordance: :ontology/mint-behavioral-subtree defcommand
   + :ontology/behavioral-subtree-minted event. Sandbox primitive lives
   in orc-service and is exercised in a separate test file.

   Covers:
   - Command + event schemas validate with :provenance mandatory
   - Missing :provenance is rejected at command boundary
   - Defcommand emits BOTH events (audit-trail + description-updated)
   - Both events carry the same target-id (audit trail joins to description)
   - Defcommand stamps :minted-at ISO timestamp
   - Defcommand stamps :scope :behavioral-subtree on the description body
   - Defcommand rejects a body whose :scope is not :behavioral-subtree
   - Minted concept appears in :ontology/concepts after R05a processor runs
   - Skos:broader link projected when :parent-behavior provided
   - Hand-authored mint path with :provenance :human-authored works"
  (:require [clojure.test :refer [deftest testing is]]
            [malli.core :as m]
            [ai.obney.orc.ontology.interface :as ontology]
            [ai.obney.orc.ontology.interface.schemas :as ontology-schemas]
            [ai.obney.orc.ontology.core.commands]
            [ai.obney.orc.ontology.core.read-models]
            [ai.obney.orc.ontology.core.todo-processors]
            [ai.obney.grain.schema-util.interface :as schema-util]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.event-store-v3.interface.schemas]
            [ai.obney.grain.query-processor.interface :as qp]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp]
            [ai.obney.grain.pubsub.interface :as pubsub]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]
            [ai.obney.grain.time.interface :as time]))

;; =============================================================================
;; Test context helpers
;; =============================================================================

(defn- create-context []
  (let [ps (pubsub/start {:type :core-async :topic-fn :event/type})
        event-store (es/start {:conn {:type :in-memory} :event-pubsub ps :logger nil})
        cache-dir (str "/tmp/r05c-test-" (random-uuid))
        cache (kv/start (lmdb/->KV-Store-LMDB {:storage-dir cache-dir :db-name "test"}))
        tenant-id (random-uuid)]
    {:event-store event-store
     :cache cache
     :tenant-id tenant-id
     :event-pubsub ps
     :command-registry (cp/global-command-registry)
     :query-registry (qp/global-query-registry)
     ::cache-dir cache-dir}))

(defn- stop-context [ctx]
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

(defn- valid-mint-body []
  {:capabilities ["x"]
   :strengths []
   :weaknesses []
   :representative-uses ["x"]
   :avoid-when ["x"]
   :summary "Agent-minted behavior under test."
   :version 1
   :consolidated-from-event-count 0})

;; =============================================================================
;; RED #1 — :ontology/mint-behavioral-subtree command schema validates
;;            with :provenance + :minted-by-* fields
;; =============================================================================

(deftest mint-command-schema-validates
  (let [schema (get @schema-util/registry* :ontology/mint-behavioral-subtree)]
    (testing "schema exists"
      (is (some? schema)
          ":ontology/mint-behavioral-subtree command schema registered"))

    (testing "validates with :provenance :agent-minted + :minted-by-* present"
      (is (m/validate schema
                      {:name "analysis-sub-class"
                       :body (valid-mint-body)
                       :provenance :agent-minted
                       :minted-by-sheet-id (random-uuid)
                       :minted-by-tick-id (random-uuid)})
          "Agent-minted mint command with sandbox provenance fields validates"))

    (testing "validates with :provenance :human-authored + no :minted-by-* (hand-authored path)"
      (is (m/validate schema
                      {:name "hand-authored-behavior"
                       :body (valid-mint-body)
                       :provenance :human-authored})
          "Hand-authored mint omits :minted-by-* fields"))

    (testing "validates with :parent-behavior present"
      (is (m/validate schema
                      {:name "child-behavior"
                       :body (valid-mint-body)
                       :provenance :human-authored
                       :parent-behavior (random-uuid)})
          ":parent-behavior is optional, accepted as UUID"))))

;; =============================================================================
;; RED #2 — missing :provenance is REJECTED (no default; load-bearing for audit)
;; =============================================================================

(deftest mint-command-rejects-missing-provenance
  (let [schema (get @schema-util/registry* :ontology/mint-behavioral-subtree)]
    (testing "schema validation FAILS when :provenance is omitted"
      (is (not (m/validate schema
                           {:name "missing-provenance-test"
                            :body (valid-mint-body)}))
          "Missing :provenance is rejected — never default"))

    (testing "schema validation FAILS when :provenance is an invalid value"
      (is (not (m/validate schema
                           {:name "bad-provenance"
                            :body (valid-mint-body)
                            :provenance :something-else}))
          ":provenance must be one of :agent-minted or :human-authored"))))

;; =============================================================================
;; RED #3 — :ontology/behavioral-subtree-minted event schema validates
;; =============================================================================

(deftest behavioral-subtree-minted-event-schema
  (let [schema (get @schema-util/registry* :ontology/behavioral-subtree-minted)]
    (testing "event schema exists"
      (is (some? schema)
          ":ontology/behavioral-subtree-minted event schema registered"))

    (testing "validates with required fields"
      (is (m/validate schema
                      {:target-id (random-uuid)
                       :name "agent-minted-behavior"
                       :provenance :agent-minted
                       :minted-by-sheet-id (random-uuid)
                       :minted-by-tick-id (random-uuid)
                       :minted-at "2026-05-28T00:00:00Z"})
          "Audit-trail event with all fields validates"))

    (testing "validates without :minted-by-* (hand-authored path)"
      (is (m/validate schema
                      {:target-id (random-uuid)
                       :name "human-authored-behavior"
                       :provenance :human-authored
                       :minted-at "2026-05-28T00:00:00Z"})
          "Hand-authored audit-trail event omits :minted-by-*"))

    (testing "rejects missing :provenance"
      (is (not (m/validate schema
                           {:target-id (random-uuid)
                            :name "no-provenance"
                            :minted-at "2026-05-28T00:00:00Z"}))
          ":provenance is mandatory on the audit-trail event too"))))

;; =============================================================================
;; Event-reading helpers
;; =============================================================================

(defn- read-events [ctx event-type]
  (into [] (es/read (:event-store ctx)
                    {:tenant-id (:tenant-id ctx)
                     :types #{event-type}})))

(defn- last-event [ctx event-type]
  (last (read-events ctx event-type)))

;; =============================================================================
;; RED #4 — defcommand emits BOTH events (audit-trail + description-updated)
;;            and both carry the same generated target-id
;; =============================================================================

(deftest mint-defcommand-emits-both-events
  (with-test-ctx [ctx]
    (cp/process-command
      (assoc ctx :command
             {:command/name :ontology/mint-behavioral-subtree
              :command/id (random-uuid)
              :command/timestamp (time/now)
              :name "agent-discovered-analysis-variant"
              :body (valid-mint-body)
              :provenance :agent-minted
              :minted-by-sheet-id (random-uuid)
              :minted-by-tick-id (random-uuid)}))

    (let [audit-event (last-event ctx :ontology/behavioral-subtree-minted)
          desc-event (last-event ctx :ontology/tree-description-updated)]

      (testing "audit-trail :ontology/behavioral-subtree-minted event landed"
        (is (some? audit-event)
            "behavioral-subtree-minted event landed"))

      (testing "description-updated :ontology/tree-description-updated event landed"
        (is (some? desc-event)
            "tree-description-updated event landed alongside the audit-trail event"))

      (testing "both events share the same target-id"
        (is (= (:target-id audit-event) (:target-id desc-event))
            "Audit-trail UUID matches description target-id — they join via :target-id"))

      (testing "description-updated body has :scope :behavioral-subtree (stamped by handler)"
        (is (= :behavioral-subtree (-> desc-event :body :scope))
            "Defcommand stamps :scope :behavioral-subtree so R05a processor picks it up"))

      (testing "audit-trail carries :name + :provenance"
        (is (= "agent-discovered-analysis-variant" (:name audit-event))
            "Name preserved")
        (is (= :agent-minted (:provenance audit-event))
            "Provenance preserved verbatim")))))

;; =============================================================================
;; RED #5 — defcommand stamps :minted-at ISO timestamp on audit event
;; =============================================================================

(deftest mint-defcommand-stamps-minted-at
  (with-test-ctx [ctx]
    (cp/process-command
      (assoc ctx :command
             {:command/name :ontology/mint-behavioral-subtree
              :command/id (random-uuid)
              :command/timestamp (time/now)
              :name "timestamp-test"
              :body (valid-mint-body)
              :provenance :human-authored}))
    (let [audit-event (last-event ctx :ontology/behavioral-subtree-minted)]
      (is (string? (:minted-at audit-event))
          ":minted-at is an ISO-formatted string stamped by the handler")
      (is (pos? (count (:minted-at audit-event)))
          ":minted-at is non-empty"))))

;; =============================================================================
;; RED #6 — defcommand rejects body with :scope ≠ :behavioral-subtree
;;            (surfaces intent mismatch)
;; =============================================================================

(deftest mint-defcommand-rejects-mismatched-scope
  (with-test-ctx [ctx]
    (testing ":scope :tree-class on the body is rejected"
      (let [result (try
                     (cp/process-command
                       (assoc ctx :command
                              {:command/name :ontology/mint-behavioral-subtree
                               :command/id (random-uuid)
                               :command/timestamp (time/now)
                               :name "wrong-scope"
                               :body (assoc (valid-mint-body) :scope :tree-class)
                               :provenance :human-authored}))
                     (catch Exception e {:caught (.getMessage e)}))]
        (is (or (some? (:caught result))
                (some? (:command-result/error result))
                (some? (:cognitect.anomalies/category result)))
            "Mismatched scope is surfaced as an error rather than silently accepted")))

    (testing "absent :scope is accepted (handler stamps it)"
      (let [body (dissoc (valid-mint-body) :scope)]
        (cp/process-command
          (assoc ctx :command
                 {:command/name :ontology/mint-behavioral-subtree
                  :command/id (random-uuid)
                  :command/timestamp (time/now)
                  :name "no-scope-on-body"
                  :body body
                  :provenance :human-authored}))
        (let [desc-event (last-event ctx :ontology/tree-description-updated)]
          (is (= :behavioral-subtree (-> desc-event :body :scope))
              "Handler stamps :scope :behavioral-subtree even when caller omits it"))))))

;; =============================================================================
;; Helper: drive the R05a processor synchronously over the new events
;; =============================================================================

(defn- drive-r05a-processor! [ctx]
  (let [handler (requiring-resolve
                  'ai.obney.orc.ontology.core.todo-processors/on-behavioral-subtree-description-updated-project-concept)]
    (doseq [e (into [] (es/read (:event-store ctx)
                                {:tenant-id (:tenant-id ctx)
                                 :types #{:ontology/tree-description-updated}}))]
      (handler (assoc ctx :event e)))))

;; =============================================================================
;; RED #7 — minted concept projects via R05a processor; parent-behavior
;;            becomes a skos:broader link
;; =============================================================================

(deftest mint-projects-via-r05a-processor
  (with-test-ctx [ctx]
    (let [parent-name "parent-behavior"
          parent-result (cp/process-command
                          (assoc ctx :command
                                 {:command/name :ontology/mint-behavioral-subtree
                                  :command/id (random-uuid)
                                  :command/timestamp (time/now)
                                  :name parent-name
                                  :body (valid-mint-body)
                                  :provenance :human-authored}))
          parent-event (last-event ctx :ontology/behavioral-subtree-minted)
          parent-id (:target-id parent-event)]

      (cp/process-command
        (assoc ctx :command
               {:command/name :ontology/mint-behavioral-subtree
                :command/id (random-uuid)
                :command/timestamp (time/now)
                :name "child-behavior"
                :body (valid-mint-body)
                :provenance :agent-minted
                :parent-behavior parent-id
                :minted-by-sheet-id (random-uuid)
                :minted-by-tick-id (random-uuid)}))

      (drive-r05a-processor! ctx)

      (let [concepts (rmp/project ctx :ontology/concepts)
            child-events (filter #(= "child-behavior" (:name %))
                                 (into [] (es/read (:event-store ctx)
                                                   {:tenant-id (:tenant-id ctx)
                                                    :types #{:ontology/behavioral-subtree-minted}})))
            child-id (:target-id (first child-events))
            parent-uri (str "behavioral-subtree:" parent-id)
            child-uri (str "behavioral-subtree:" child-id)]

        (testing "parent behavioral-subtree concept exists in graph"
          (is (contains? concepts parent-uri)
              "Parent minted concept projected to concept graph"))

        (testing "child behavioral-subtree concept exists in graph"
          (is (contains? concepts child-uri)
              "Child minted concept projected to concept graph"))

        (testing "child carries skos:broader link to parent"
          (is (contains? (get-in concepts [child-uri :broader]) parent-uri)
              "Child's :broader set includes parent's URI (skos:broader projected)"))

        (testing "child concept scope is :behavioral-subtree"
          (is (= :behavioral-subtree (get-in concepts [child-uri :scope]))))))))

;; =============================================================================
;; RED #8 — hand-authored mint path: :provenance :human-authored,
;;            no :minted-by-* fields
;; =============================================================================

(deftest hand-authored-mint-path
  (with-test-ctx [ctx]
    (cp/process-command
      (assoc ctx :command
             {:command/name :ontology/mint-behavioral-subtree
              :command/id (random-uuid)
              :command/timestamp (time/now)
              :name "operator-curated-behavior"
              :body (valid-mint-body)
              :provenance :human-authored}))

    (let [audit-event (last-event ctx :ontology/behavioral-subtree-minted)]
      (testing "audit-trail tagged :human-authored"
        (is (= :human-authored (:provenance audit-event))
            "Hand-authored entries are distinguishable from agent-minted"))
      (testing "audit-trail omits :minted-by-sheet-id and :minted-by-tick-id"
        (is (not (contains? audit-event :minted-by-sheet-id))
            "No sandbox provenance fields on hand-authored mint")
        (is (not (contains? audit-event :minted-by-tick-id))
            "No sandbox provenance fields on hand-authored mint")))))

;; =============================================================================
;; C-Loop-2 D4 — Stable target-id derivation: same (name, parent-behavior) →
;;                same target-id across calls (idempotent at the UUID layer)
;; =============================================================================
;;
;; The mint defcommand previously generated a fresh random-uuid on every
;; call. If the agent accidentally called (mint-behavior! "same-name" ...)
;; twice within a session, that produced two distinct concepts polluting
;; the graph. Per C-Loop-2 spec criterion D4, same name + same parent must
;; map to the same target-id so duplicate mints become idempotent at the
;; UUID layer. (Downstream events still log twice — the audit trail is
;; preserved; what's idempotent is the IDENTITY of the minted concept.)

(deftest c-loop-2-d4-same-name-and-parent-yield-same-target-id
  (testing "C-Loop-2 D4: minting twice with the same (name, parent) produces the SAME target-id"
    (with-test-ctx [ctx]
      (let [_ (cp/process-command
                (assoc ctx :command
                       {:command/name :ontology/mint-behavioral-subtree
                        :command/id (random-uuid)
                        :command/timestamp (time/now)
                        :name "idempotency-test-behavior"
                        :body (valid-mint-body)
                        :provenance :human-authored}))
            first-events (into [] (es/read (:event-store ctx)
                                            {:tenant-id (:tenant-id ctx)
                                             :types #{:ontology/behavioral-subtree-minted}}))
            first-target-id (:target-id (last first-events))

            _ (cp/process-command
                (assoc ctx :command
                       {:command/name :ontology/mint-behavioral-subtree
                        :command/id (random-uuid)
                        :command/timestamp (time/now)
                        :name "idempotency-test-behavior"
                        :body (valid-mint-body)
                        :provenance :human-authored}))
            second-events (into [] (es/read (:event-store ctx)
                                             {:tenant-id (:tenant-id ctx)
                                              :types #{:ontology/behavioral-subtree-minted}}))
            second-target-id (:target-id (last second-events))]
        (is (= first-target-id second-target-id)
            "Same name + same parent (nil here) must derive to the same target-id; current random-uuid impl violates this")))))

(deftest c-loop-2-d4-same-name-different-parent-yields-different-target-id
  (testing "C-Loop-2 D4: same name with DIFFERENT parent produces DIFFERENT target-ids (parent is part of identity)"
    (with-test-ctx [ctx]
      (let [parent-a (random-uuid)
            parent-b (random-uuid)
            _ (cp/process-command
                (assoc ctx :command
                       {:command/name :ontology/mint-behavioral-subtree
                        :command/id (random-uuid)
                        :command/timestamp (time/now)
                        :name "shared-name"
                        :body (valid-mint-body)
                        :provenance :human-authored
                        :parent-behavior parent-a}))
            events-after-a (into [] (es/read (:event-store ctx)
                                              {:tenant-id (:tenant-id ctx)
                                               :types #{:ontology/behavioral-subtree-minted}}))
            id-under-a (:target-id (last events-after-a))

            _ (cp/process-command
                (assoc ctx :command
                       {:command/name :ontology/mint-behavioral-subtree
                        :command/id (random-uuid)
                        :command/timestamp (time/now)
                        :name "shared-name"
                        :body (valid-mint-body)
                        :provenance :human-authored
                        :parent-behavior parent-b}))
            events-after-b (into [] (es/read (:event-store ctx)
                                              {:tenant-id (:tenant-id ctx)
                                               :types #{:ontology/behavioral-subtree-minted}}))
            id-under-b (:target-id (last events-after-b))]
        (is (not= id-under-a id-under-b)
            "Same name UNDER different parents must produce different target-ids — parent is part of the identity")))))

(deftest c-loop-2-d4-different-name-same-parent-yields-different-target-id
  (testing "C-Loop-2 D4: different names with the same parent produce different target-ids"
    (with-test-ctx [ctx]
      (let [parent-id (random-uuid)
            _ (cp/process-command
                (assoc ctx :command
                       {:command/name :ontology/mint-behavioral-subtree
                        :command/id (random-uuid)
                        :command/timestamp (time/now)
                        :name "name-a"
                        :body (valid-mint-body)
                        :provenance :human-authored
                        :parent-behavior parent-id}))
            events-1 (into [] (es/read (:event-store ctx)
                                        {:tenant-id (:tenant-id ctx)
                                         :types #{:ontology/behavioral-subtree-minted}}))
            id-a (:target-id (last events-1))

            _ (cp/process-command
                (assoc ctx :command
                       {:command/name :ontology/mint-behavioral-subtree
                        :command/id (random-uuid)
                        :command/timestamp (time/now)
                        :name "name-b"
                        :body (valid-mint-body)
                        :provenance :human-authored
                        :parent-behavior parent-id}))
            events-2 (into [] (es/read (:event-store ctx)
                                        {:tenant-id (:tenant-id ctx)
                                         :types #{:ontology/behavioral-subtree-minted}}))
            id-b (:target-id (last events-2))]
        (is (not= id-a id-b)
            "Different names under same parent must produce different target-ids")))))
