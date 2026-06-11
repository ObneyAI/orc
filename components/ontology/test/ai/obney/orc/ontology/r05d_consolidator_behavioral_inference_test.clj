(ns ai.obney.orc.ontology.r05d-consolidator-behavioral-inference-test
  "R05d — Consolidator behavioral inference + composes-into edge growth.

   Covers:
   - description-body schema accepts optional :behavioral-subtree-ids
   - maybe-hydrate-behavioral-subtree-ids parallel helper (sibling of
     maybe-hydrate-parent-tree-id): first-time, sticky, orphan
   - non-tree-fingerprint passthrough
   - dispatch-observed-composes-into-edges! grows behavior:composes-into
     edges when inferred (behavior, shell) pairs aren't yet in the graph
   - idempotency: re-running edge growth doesn't duplicate"
  (:require [clojure.test :refer [deftest testing is]]
            [malli.core :as m]
            [ai.obney.orc.ontology.interface :as ontology]
            [ai.obney.orc.ontology.interface.schemas]
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
        cache-dir (str "/tmp/r05d-test-" (random-uuid))
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

(defn- base-body []
  {:capabilities ["x"]
   :strengths []
   :weaknesses []
   :representative-uses ["x"]
   :avoid-when ["x"]
   :summary "x"
   :version 1
   :consolidated-from-event-count 1})

;; =============================================================================
;; RED #1 — description-body schema accepts optional :behavioral-subtree-ids
;; =============================================================================

(deftest description-body-accepts-behavioral-subtree-ids
  (testing "validates with vector of UUIDs"
    (is (m/validate ontology-schemas/description-body
                    (assoc (base-body) :behavioral-subtree-ids [(random-uuid) (random-uuid)]))
        "UUID-vector :behavioral-subtree-ids accepted"))

  (testing "validates with vector of strings"
    (is (m/validate ontology-schemas/description-body
                    (assoc (base-body) :behavioral-subtree-ids ["seed:behavior:analysis"]))
        "String-vector :behavioral-subtree-ids accepted"))

  (testing "validates with mixed UUIDs and strings"
    (is (m/validate ontology-schemas/description-body
                    (assoc (base-body) :behavioral-subtree-ids [(random-uuid) "seed:behavior:x"]))
        "Mixed vector accepted"))

  (testing "legacy body (no :behavioral-subtree-ids) remains valid"
    (is (m/validate ontology-schemas/description-body (base-body))
        "Absence of :behavioral-subtree-ids is fine — additive only"))

  (testing "rejects non-vector :behavioral-subtree-ids"
    (is (not (m/validate ontology-schemas/description-body
                         (assoc (base-body) :behavioral-subtree-ids "single-string")))
        "Scalar :behavioral-subtree-ids is rejected (must be vector)"))

  (testing "rejects vector of wrong type"
    (is (not (m/validate ontology-schemas/description-body
                         (assoc (base-body) :behavioral-subtree-ids [42 99])))
        "Integer entries are rejected")))

;; =============================================================================
;; classify-behaviors stub
;; =============================================================================

(defn- behavior-candidate [target-id confidence reasoning]
  {:behavior-id target-id
   :confidence confidence
   :was-fresh-mint? false
   :reasoning reasoning
   :rerank-source :reranker})

(defn- fresh-mint-marker []
  {:behavior-id (random-uuid)
   :confidence 0.0
   :was-fresh-mint? true
   :reasoning "No candidate above threshold; minting fresh"
   :rerank-source nil})

;; =============================================================================
;; RED #2 — first-time tree-fingerprint: classify-behaviors called →
;;            :behavioral-subtree-ids assoc'ed with top-N above-threshold ids
;; =============================================================================

(deftest hydrate-behavioral-first-time-stamps-top-n
  (testing "First-time tree-fingerprint: top-N behavior IDs assoc'd onto body"
    (let [target-id (random-uuid)
          b1 (random-uuid)
          b2 (random-uuid)
          calls (atom [])]
      (with-redefs [ontology/classify-behaviors
                    (fn [_ctx opts]
                      (swap! calls conj opts)
                      {:behaviors [(behavior-candidate b1 0.92 "analysis dominates")
                                   (behavior-candidate b2 0.85 "synthesis composes")]
                       :rerank-fallback? false})]
        (let [hydrate (requiring-resolve
                        'ai.obney.orc.ontology.core.consolidator/maybe-hydrate-behavioral-subtree-ids)
              result (hydrate {} :tree-fingerprint target-id nil (base-body))]
          (is (= [b1 b2] (:behavioral-subtree-ids result))
              "Top-N behavior IDs assoc'd onto the body, in classifier order")
          (is (= 1 (count @calls))
              "classify-behaviors called once for first-time consolidation")
          (let [opts (first @calls)]
            (is (= target-id (:structural-context opts))
                ":structural-context = the tree-class id being consolidated")
            (is (string? (:task-signature opts))
                "Task signature built from the body's summary + capabilities + uses")
            (is (number? (:threshold opts))
                "Threshold passed explicitly")))))))

;; =============================================================================
;; RED #3 — STICKY: subsequent consolidation preserves prior :behavioral-subtree-ids;
;;            classify-behaviors NOT re-called
;; =============================================================================

(deftest hydrate-behavioral-sticky-on-subsequent-consolidation
  (testing "Existing :behavioral-subtree-ids preserved; classify-behaviors NOT called"
    (let [existing-ids [(random-uuid) (random-uuid)]
          target-id (random-uuid)
          current {:summary "prior" :version 1
                   :behavioral-subtree-ids existing-ids}
          new-body (assoc (base-body) :summary "v2" :version 2 :consolidated-from-event-count 8)
          calls (atom 0)]
      (with-redefs [ontology/classify-behaviors
                    (fn [& _]
                      (swap! calls inc)
                      (throw (ex-info "should not be called on sticky path" {})))]
        (let [hydrate (requiring-resolve
                        'ai.obney.orc.ontology.core.consolidator/maybe-hydrate-behavioral-subtree-ids)
              result (hydrate {} :tree-fingerprint target-id current new-body)]
          (is (zero? @calls)
              "classify-behaviors NOT called when current description already has :behavioral-subtree-ids")
          (is (= existing-ids (:behavioral-subtree-ids result))
              "Existing inferred IDs preserved onto the new body"))))))

;; =============================================================================
;; RED #4 — ORPHAN: classify-behaviors returns fresh-mint marker →
;;            body has NO :behavioral-subtree-ids (parallel to ::orphan-tree-class-created)
;; =============================================================================

(deftest hydrate-behavioral-orphan-omits-field-on-fresh-mint
  (testing "Fresh-mint marker → no :behavioral-subtree-ids on body"
    ;; The ::orphan-behavioral-subtree-inferred log is observability and is
    ;; verified at R05e live verify; mu/log is a macro and not
    ;; with-redefs-able. Structural assertion: body emerges without the
    ;; :behavioral-subtree-ids key under fresh-mint.
    (let [target-id (random-uuid)]
      (with-redefs [ontology/classify-behaviors
                    (fn [& _]
                      {:behaviors [(fresh-mint-marker)]
                       :rerank-fallback? false})]
        (let [hydrate (requiring-resolve
                        'ai.obney.orc.ontology.core.consolidator/maybe-hydrate-behavioral-subtree-ids)
              result (hydrate {} :tree-fingerprint target-id nil (base-body))]
          (is (not (contains? result :behavioral-subtree-ids))
              "Orphan path: no :behavioral-subtree-ids field on body"))))))

;; =============================================================================
;; RED #5 — non-tree-fingerprint targets pass through unchanged
;;            classify-behaviors not called for node-type / node-instance
;; =============================================================================

(deftest hydrate-behavioral-passthrough-for-non-tree-fingerprint
  (let [calls (atom 0)]
    (with-redefs [ontology/classify-behaviors
                  (fn [& _]
                    (swap! calls inc)
                    (throw (ex-info "should not be called for non-tree-fingerprint" {})))]
      (let [hydrate (requiring-resolve
                      'ai.obney.orc.ontology.core.consolidator/maybe-hydrate-behavioral-subtree-ids)]

        (testing "node-type target passes through unchanged"
          (let [result (hydrate {} :node-type :llm nil (base-body))]
            (is (= (base-body) result)
                "Node-type body unchanged")))

        (testing "node-instance target passes through unchanged"
          (let [result (hydrate {} :node-instance [(random-uuid) (random-uuid)]
                                nil (base-body))]
            (is (= (base-body) result)
                "Node-instance body unchanged")))

        (testing "classify-behaviors NOT called for either"
          (is (zero? @calls)
              "Helper skips classify-behaviors for non-tree-fingerprint granularities"))))))

;; =============================================================================
;; Helpers for edge-growth tests
;; =============================================================================

(defn- emit-behavioral-seed!
  "Seed a behavioral-subtree concept via R05a's processor path so the
   concept graph has the behavior URI before edge-growth runs."
  [ctx behavior-id]
  (cp/process-command
    (assoc ctx :command
           {:command/name :ontology/record-tree-description
            :command/id (random-uuid)
            :command/timestamp (time/now)
            :target-id behavior-id
            :body (assoc (base-body) :scope :behavioral-subtree)}))
  (let [handler (requiring-resolve
                  'ai.obney.orc.ontology.core.todo-processors/on-behavioral-subtree-description-updated-project-concept)]
    (doseq [e (into [] (es/read (:event-store ctx)
                                {:tenant-id (:tenant-id ctx)
                                 :types #{:ontology/tree-description-updated}}))]
      (handler (assoc ctx :event e)))))

(defn- ensure-tree-class-concept!
  "Lazy-create a tree-class concept (the structural shell) directly so the
   composes-into edge has a target URI to point at."
  [ctx shell-id]
  (cp/process-command
    (assoc ctx :command
           {:command/name :ontology/create-concept
            :command/id (random-uuid)
            :command/timestamp (time/now)
            :ontology-id (java.util.UUID/nameUUIDFromBytes
                           (.getBytes "tree-class-ontology" "UTF-8"))
            :uri (str "tree-class:" shell-id)
            :label (str shell-id)
            :description (str "Tree-class concept for " shell-id)
            :scope :tree-class
            :broader []
            :indicators []})))

(defn- count-composes-into [ctx behavior-uri]
  (count (get-in (rmp/project ctx :ontology/concepts)
                 [behavior-uri :composes-into] #{})))

;; =============================================================================
;; RED #6 — dispatch-observed-composes-into-edges! creates missing edges
;; =============================================================================

(deftest dispatch-edges-creates-missing-composes-into
  (with-test-ctx [ctx]
    (let [shell-id (random-uuid)
          behavior-id-1 (random-uuid)
          behavior-id-2 (random-uuid)
          behavior-uri-1 (str "behavioral-subtree:" behavior-id-1)
          behavior-uri-2 (str "behavioral-subtree:" behavior-id-2)
          shell-uri (str "tree-class:" shell-id)]
      ;; Seed concepts on both sides
      (emit-behavioral-seed! ctx behavior-id-1)
      (emit-behavioral-seed! ctx behavior-id-2)
      (ensure-tree-class-concept! ctx shell-id)

      (testing "before dispatch: behaviors have no composes-into edges"
        (is (zero? (count-composes-into ctx behavior-uri-1)))
        (is (zero? (count-composes-into ctx behavior-uri-2))))

      (let [dispatch! (requiring-resolve
                        'ai.obney.orc.ontology.core.consolidator/dispatch-observed-composes-into-edges!)]
        (dispatch! ctx shell-id [behavior-id-1 behavior-id-2]))

      (let [concepts (rmp/project ctx :ontology/concepts)]
        (testing "behavior 1's composes-into now includes the shell"
          (is (contains? (get-in concepts [behavior-uri-1 :composes-into] #{})
                         shell-uri)
              "Edge dispatched"))
        (testing "behavior 2's composes-into now includes the shell"
          (is (contains? (get-in concepts [behavior-uri-2 :composes-into] #{})
                         shell-uri)
              "Edge dispatched"))
        (testing "shell's :composed-by reverse set contains both behaviors"
          (is (= #{behavior-uri-1 behavior-uri-2}
                 (get-in concepts [shell-uri :composed-by] #{}))))))))

;; =============================================================================
;; RED #7 — IDEMPOTENCY: edges already present are not duplicated
;; =============================================================================

(deftest dispatch-edges-idempotent-on-rerun
  (with-test-ctx [ctx]
    (let [shell-id (random-uuid)
          behavior-id (random-uuid)
          behavior-uri (str "behavioral-subtree:" behavior-id)]
      (emit-behavioral-seed! ctx behavior-id)
      (ensure-tree-class-concept! ctx shell-id)

      (let [dispatch! (requiring-resolve
                        'ai.obney.orc.ontology.core.consolidator/dispatch-observed-composes-into-edges!)]
        (dispatch! ctx shell-id [behavior-id])
        (let [edge-count-after-first (count-composes-into ctx behavior-uri)]
          (dispatch! ctx shell-id [behavior-id])
          (let [edge-count-after-second (count-composes-into ctx behavior-uri)]
            (testing "edge-count is 1 after first dispatch"
              (is (= 1 edge-count-after-first)))
            (testing "second dispatch is a no-op"
              (is (= edge-count-after-first edge-count-after-second)
                  "Re-running dispatch on the same input doesn't add duplicate edges"))))))))

;; =============================================================================
;; RED #8 — dispatch-edges is a no-op when behavioral-subtree-ids is empty
;; =============================================================================

(deftest dispatch-edges-no-op-on-empty-input
  (with-test-ctx [ctx]
    (let [shell-id (random-uuid)]
      (ensure-tree-class-concept! ctx shell-id)
      (let [dispatch! (requiring-resolve
                        'ai.obney.orc.ontology.core.consolidator/dispatch-observed-composes-into-edges!)]
        (dispatch! ctx shell-id nil)
        (dispatch! ctx shell-id [])
        (testing "shell concept has no :composed-by set after empty dispatches"
          (let [shell-uri (str "tree-class:" shell-id)]
            (is (empty? (get-in (rmp/project ctx :ontology/concepts)
                                [shell-uri :composed-by] #{}))
                "No edges created for nil/empty input")))))))
