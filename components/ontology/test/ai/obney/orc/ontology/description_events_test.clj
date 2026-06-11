(ns ai.obney.orc.ontology.description-events-test
  "Tests for C-2a-1's new description event types + commands + read-model.

   Three new event types — one per granularity:
     :ontology/node-type-description-updated
     :ontology/node-instance-description-updated
     :ontology/tree-description-updated

   Three new commands (1:1 with events, following the
   record-tree-strength/record-tree-weakness idiom):
     :ontology/record-node-type-description
     :ontology/record-node-instance-description
     :ontology/record-tree-description

   All three event types share a common body shape (description-body)
   carrying principle-shaped strengths/weaknesses, capabilities,
   representative-uses, summary, version, and consolidated-from-event-count
   fields."
  (:require [clojure.test :refer [deftest testing is]]
            [malli.core :as m]
            [ai.obney.orc.ontology.interface.schemas]
            [ai.obney.orc.ontology.core.commands]
            [ai.obney.orc.ontology.core.read-models]
            [ai.obney.orc.ontology.interface :as ontology]
            [ai.obney.grain.schema-util.interface :as schema-util]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.query-processor.interface :as qp]
            [ai.obney.grain.pubsub.interface :as pubsub]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]
            [ai.obney.grain.time.interface :as time]
            [seed-descriptions :as seeds]))

;; =============================================================================
;; Test context helpers (mirrors the in-memory pattern from C-1's probe)
;; =============================================================================

(defn- create-context []
  (let [ps (pubsub/start {:type :core-async :topic-fn :event/type})
        event-store (es/start {:conn {:type :in-memory} :event-pubsub ps :logger nil})
        cache-dir (str "/tmp/c2a1-test-" (random-uuid))
        cache (kv/start (lmdb/->KV-Store-LMDB {:storage-dir cache-dir :db-name "test"}))]
    {:event-store event-store
     :cache cache
     :tenant-id (random-uuid)
     :command-registry (cp/global-command-registry)
     :query-registry (qp/global-query-registry)
     :event-pubsub ps
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

;; =============================================================================
;; Fixture — a minimal but well-formed description body
;; =============================================================================

(def sample-description-body
  "A small but conforming description body — one strength, one weakness,
   small capability/uses/avoid-when lists, a summary, version, count.

   Hand-authored seeds will be richer; this is the minimum a well-formed
   event must satisfy."
  {:capabilities ["chunks large documents" "extracts entities per chunk"]
   :strengths    [{:trait "bounded :max-concurrency on :map-each over chunks"
                   :good-when "input is a large chunked document"
                   :recommended-pattern "[:map-each {:from :chunks :as :chunk :into :results :max-concurrency 3} [:llm {...}]]"
                   :confidence 1.0
                   :evidence-count 1
                   :first-observed-at "2026-05-20T00:00:00Z"
                   :last-reinforced-at "2026-05-20T00:00:00Z"}]
   :weaknesses   [{:trait "rate-limit exhaustion under unbounded parallelism"
                   :avoid-when "input has >6 chunks AND :max-concurrency is unset"
                   :recommended-alternative "set :max-concurrency to 3 on the :map-each"
                   :confidence 1.0
                   :evidence-count 1
                   :first-observed-at "2026-05-20T00:00:00Z"
                   :last-reinforced-at "2026-05-20T00:00:00Z"}]
   :representative-uses ["document-analysis benchmark task"]
   :avoid-when   ["very small inputs where chunking adds overhead"]
   :summary      "When extracting from chunked documents, prefer bounded map-each concurrency."
   :version 1
   :consolidated-from-event-count 0})

;; =============================================================================
;; RED #1 — schema validates a well-formed event
;; =============================================================================

(deftest node-type-description-updated-event-schema-validates
  (testing "A well-formed :ontology/node-type-description-updated event passes its schema"
    (let [event {:target-type :node-type
                 :target-id :llm
                 :body sample-description-body
                 :recorded-at "2026-05-26T00:00:00Z"}
          schema (get @schema-util/registry* :ontology/node-type-description-updated)]
      (is (some? schema)
          "The schema for :ontology/node-type-description-updated should be registered in the schema-util registry")
      (is (m/validate schema event)
          (str "Event should validate. Explanation: "
               (when schema (pr-str (m/explain schema event))))))))

;; =============================================================================
;; RED #2 — round-trip via the command for one granularity (node-type)
;; =============================================================================

(deftest record-node-type-description-command-emits-matching-event
  (testing "Dispatching :ontology/record-node-type-description writes a matching event"
    (with-test-ctx [ctx]
      (let [cmd {:command/name :ontology/record-node-type-description
                 :command/id (random-uuid)
                 :command/timestamp (time/now)
                 :target-id :llm
                 :body sample-description-body}
            result (cp/process-command (assoc ctx :command cmd))
            events (:command-result/events result)]
        (is (= 1 (count events))
            "Exactly one event should be emitted")
        (let [e (first events)]
          (is (= :ontology/node-type-description-updated (:event/type e))
              "Event type should be :ontology/node-type-description-updated")
          (is (= :node-type (:target-type e))
              "Event should carry the granularity discriminator")
          (is (= :llm (:target-id e))
              "Event should carry the node-type keyword")
          (is (= sample-description-body (:body e))
              "Event body should match the command's body verbatim")
          (is (string? (:recorded-at e))
              "Event should stamp :recorded-at"))))))

;; =============================================================================
;; RED #3 — read-model get-description returns the LATEST body for a target
;; =============================================================================

(deftest get-description-returns-latest-body
  (testing "After two consecutive record-node-type-description commands, get-description returns the second body"
    (with-test-ctx [ctx]
      (let [body-v1 (assoc sample-description-body :version 1)
            body-v2 (assoc sample-description-body :version 2
                           :summary "Updated summary after consolidation v2")]
        ;; Record first version
        (cp/process-command (assoc ctx :command
                                   {:command/name :ontology/record-node-type-description
                                    :command/id (random-uuid)
                                    :command/timestamp (time/now)
                                    :target-id :llm
                                    :body body-v1}))
        (Thread/sleep 50)  ;; ensure timestamps differ
        ;; Record second version for the same target
        (cp/process-command (assoc ctx :command
                                   {:command/name :ontology/record-node-type-description
                                    :command/id (random-uuid)
                                    :command/timestamp (time/now)
                                    :target-id :llm
                                    :body body-v2}))
        (Thread/sleep 50)  ;; let read model project
        (let [current (ontology/get-description ctx :node-type :llm)]
          (is (some? current)
              "get-description should return a non-nil body for the target")
          (is (= 2 (:version current))
              "get-description should return the LATEST body (version 2)")
          (is (= "Updated summary after consolidation v2" (:summary current))
              "Body fields should match the second event's body"))))))

;; =============================================================================
;; RED #4 — get-description-history returns chronological versions
;; =============================================================================

(deftest get-description-history-returns-chronological-versions
  (testing "After three sequential consolidations, get-description-history returns 3 entries in order"
    (with-test-ctx [ctx]
      (let [bodies (mapv (fn [v] (assoc sample-description-body
                                        :version v
                                        :summary (str "Version " v " summary")))
                         [1 2 3])]
        (doseq [b bodies]
          (cp/process-command (assoc ctx :command
                                     {:command/name :ontology/record-node-type-description
                                      :command/id (random-uuid)
                                      :command/timestamp (time/now)
                                      :target-id :map-each
                                      :body b}))
          (Thread/sleep 30))
        (Thread/sleep 50)
        (let [history (ontology/get-description-history ctx :node-type :map-each)]
          (is (= 3 (count history))
              "History should contain exactly 3 entries (one per recording)")
          (is (= [1 2 3] (mapv #(get-in % [:body :version]) history))
              "History entries should be in chronological emission order"))))))

;; =============================================================================
;; RED #5 — fan-out to the other two granularities (node-instance + tree)
;; =============================================================================

(deftest record-node-instance-description-fans-out-to-event
  (testing ":ontology/record-node-instance-description emits the matching event with the [sheet-id node-id] target-id"
    (with-test-ctx [ctx]
      (let [sheet-id (random-uuid)
            node-id (random-uuid)
            cmd {:command/name :ontology/record-node-instance-description
                 :command/id (random-uuid)
                 :command/timestamp (time/now)
                 :target-id [sheet-id node-id]
                 :body sample-description-body}
            result (cp/process-command (assoc ctx :command cmd))
            events (:command-result/events result)]
        (is (= 1 (count events)))
        (let [e (first events)]
          (is (= :ontology/node-instance-description-updated (:event/type e)))
          (is (= :node-instance (:target-type e)))
          (is (= [sheet-id node-id] (:target-id e))))))))

(deftest record-tree-description-fans-out-to-event
  (testing ":ontology/record-tree-description emits the matching event with the fingerprint string target-id"
    (with-test-ctx [ctx]
      (let [fingerprint "sha-stub-abc123def456"
            cmd {:command/name :ontology/record-tree-description
                 :command/id (random-uuid)
                 :command/timestamp (time/now)
                 :target-id fingerprint
                 :body sample-description-body}
            result (cp/process-command (assoc ctx :command cmd))
            events (:command-result/events result)]
        (is (= 1 (count events)))
        (let [e (first events)]
          (is (= :ontology/tree-description-updated (:event/type e)))
          (is (= :tree-fingerprint (:target-type e)))
          (is (= fingerprint (:target-id e))))))))

(deftest record-tree-class-description-fans-out-to-event
  (testing "C-Loop-1: :ontology/record-tree-class-description emits :ontology/tree-description-updated with :target-type :tree-class"
    (with-test-ctx [ctx]
      (let [tree-class-id (random-uuid)
            cmd {:command/name :ontology/record-tree-class-description
                 :command/id (random-uuid)
                 :command/timestamp (time/now)
                 :target-id tree-class-id
                 :body sample-description-body}
            result (cp/process-command (assoc ctx :command cmd))
            events (:command-result/events result)]
        (is (= 1 (count events)))
        (let [e (first events)]
          (is (= :ontology/tree-description-updated (:event/type e)))
          (is (= :tree-class (:target-type e)))
          (is (= tree-class-id (:target-id e))))
        (Thread/sleep 100)
        (is (= (:summary sample-description-body)
               (:summary (ontology/get-description ctx :tree-class tree-class-id)))
            "get-description :tree-class returns the recorded body")
        (is (nil? (ontology/get-description ctx :tree-fingerprint tree-class-id))
            ":tree-class write does not leak into :tree-fingerprint scope")))))

;; =============================================================================
;; RED #6 — read model handles all three granularities cleanly
;; =============================================================================

(deftest read-model-handles-all-three-granularities
  (testing "Write one description per granularity; get-description returns the right body for each"
    (with-test-ctx [ctx]
      (let [sheet-id (random-uuid)
            node-id (random-uuid)
            fingerprint "sha-tree-xyz789"
            nt-body (assoc sample-description-body :summary "node-type body")
            ni-body (assoc sample-description-body :summary "node-instance body")
            tr-body (assoc sample-description-body :summary "tree body")]
        ;; Write one of each
        (cp/process-command (assoc ctx :command
                                   {:command/name :ontology/record-node-type-description
                                    :command/id (random-uuid)
                                    :command/timestamp (time/now)
                                    :target-id :llm
                                    :body nt-body}))
        (cp/process-command (assoc ctx :command
                                   {:command/name :ontology/record-node-instance-description
                                    :command/id (random-uuid)
                                    :command/timestamp (time/now)
                                    :target-id [sheet-id node-id]
                                    :body ni-body}))
        (cp/process-command (assoc ctx :command
                                   {:command/name :ontology/record-tree-description
                                    :command/id (random-uuid)
                                    :command/timestamp (time/now)
                                    :target-id fingerprint
                                    :body tr-body}))
        (Thread/sleep 100)

        ;; Each granularity returns the correct body
        (is (= "node-type body"
               (:summary (ontology/get-description ctx :node-type :llm))))
        (is (= "node-instance body"
               (:summary (ontology/get-description ctx :node-instance [sheet-id node-id]))))
        (is (= "tree body"
               (:summary (ontology/get-description ctx :tree-fingerprint fingerprint))))

        ;; Cross-granularity queries return nil (no cross-contamination)
        (is (nil? (ontology/get-description ctx :node-type fingerprint))
            "Querying :node-type with a fingerprint string should return nil")
        (is (nil? (ontology/get-description ctx :tree-fingerprint :llm))
            "Querying :tree-fingerprint with a node-type keyword should return nil")))))

;; =============================================================================
;; RED #7 — first hand-authored seed loads via seed-one! and is queryable
;; =============================================================================

(deftest first-hand-authored-seed-loads-and-is-queryable
  (testing "Calling seed-one! with the :llm node-type seed makes it queryable via get-description"
    (with-test-ctx [ctx]
      (seeds/seed-one! ctx :node-type seeds/llm-node-type-seed)
      (Thread/sleep 100)
      (let [body (ontology/get-description ctx :node-type :llm)]
        (is (some? body)
            "Seed should be queryable after seed-one!")
        (is (= (:body seeds/llm-node-type-seed) body)
            "Returned body should match the seed's :body verbatim")))))

;; =============================================================================
;; RED #8 — every strength/weakness entry across all seeds is principle-shaped
;; =============================================================================
;;
;; Counts grow as new seed batches land:
;; - C-2a-1 baseline:    22 (10 node-type + 12 tree-fingerprint)
;; - R02 children added: 33 (10 + 23 tree-fingerprint)
;; - R05a behavioral:    44 (10 + 23 + 11 behavioral-subtree)
;; - R07 + Investigation: 45 (10 + 23 + 12 behavioral-subtree)

(deftest all-seeds-are-principle-shaped
  (testing "Every strength/weakness entry across all seeds has the principle-entry shape (trait + guard + recommendation + confidence + evidence-count)"
    (let [all-seeds (concat seeds/all-node-type-seeds
                            seeds/all-tree-fingerprint-seeds
                            seeds/all-behavioral-subtree-seeds)]
      (is (= 45 (count all-seeds))
          (str "Expected 45 hand-authored seeds total (10 node-type + 23 tree-fingerprint + 12 behavioral-subtree). Got " (count all-seeds) "."))
      (doseq [{:keys [target-id body]} all-seeds
              entry (concat (:strengths body) (:weaknesses body))]
        (is (seeds/principle-shaped? entry)
            (str "Seed " (pr-str target-id)
                 " has a non-principle-shaped entry: "
                 (pr-str entry)))))))

;; =============================================================================
;; RED #9 — every seed snippet either validates via rlm-dsl->orc-dsl OR uses
;;           a documented-unsupported node type (:fallback / :condition)
;; =============================================================================
;;
;; Some snippets across the seed corpus are KNOWN to be canonical-DSL-only —
;; they describe patterns the RLM transformer cannot emit yet (:fallback,
;; :condition). Those are tracked by issues R-Fallback and R-Condition;
;; the matching seeds carry an explicit "NOT currently emit-able by RLM"
;; caveat. The validator allows them through. EVERY other snippet must
;; transform without error — that's the load-bearing quality gate.

(defn- uses-pending-extension?
  "Walk a form and return the first :fallback or :condition node-type found,
   or nil if neither appears. Used to allow-list documented-unsupported
   snippets in the seed corpus."
  [form]
  (cond
    (and (sequential? form) (#{:fallback :condition} (first form)))
    (first form)

    (sequential? form)
    (some uses-pending-extension? form)

    :else nil))

(defn- snippet-strings-from-body
  "Return all :recommended-pattern strings present in a description body's
   strengths + weaknesses. Per the body-shape contract, :recommended-pattern
   is a 'valid DSL snippet' and MUST validate. :recommended-alternative
   is documented as 'actionable advice' (prose allowed) so it's not
   validated here."
  [body]
  (->> (concat (:strengths body) (:weaknesses body))
       (map :recommended-pattern)
       (filter string?)))

;; =============================================================================
;; RED #11 — seed-all! emits every authored seed + each is queryable through the
;;            public get-description interface
;; =============================================================================
;;
;; Counts grow as new seed batches land — see all-seeds-are-principle-shaped
;; for the running tally. R05a behavioral-subtree seeds are emitted via the
;; same :ontology/record-tree-description command (their body carries
;; :scope :behavioral-subtree to route to the R05a reactive processor) so
;; they're queryable via get-description :tree-fingerprint just like the
;; structural tree-fingerprint seeds.

(deftest seed-all-emits-everything-and-each-is-queryable
  (testing "seed-all! emits every authored seed; get-description retrieves each one's body verbatim"
    (with-test-ctx [ctx]
      (let [results (seeds/seed-all! ctx)
            tree-fp-count (count seeds/all-tree-fingerprint-seeds)
            expected (+ (count seeds/all-node-type-seeds)
                        tree-fp-count
                        ;; C-Loop-1: each tree-fingerprint seed is also
                        ;; recorded under :tree-class scope (same target-id,
                        ;; same body) so the Living Description loop's
                        ;; consolidator has a non-nil current-description
                        ;; on its first cycle.
                        tree-fp-count
                        (count seeds/all-behavioral-subtree-seeds))]
        (is (= expected (count results))
            (str "seed-all! should emit " expected
                 " commands (10 node-type + 23 tree-fingerprint + 23 tree-class duplicates + 12 behavioral-subtree). Got "
                 (count results))))
      (Thread/sleep 200)
      (doseq [{:keys [target-id body]} seeds/all-node-type-seeds]
        (is (= body (ontology/get-description ctx :node-type target-id))
            (str "Node-type seed " (pr-str target-id) " should be queryable verbatim")))
      (doseq [{:keys [target-id body]} seeds/all-tree-fingerprint-seeds]
        (is (= body (ontology/get-description ctx :tree-fingerprint target-id))
            (str "Tree-fingerprint seed " (pr-str target-id) " should be queryable verbatim"))
        (is (= body (ontology/get-description ctx :tree-class target-id))
            (str "C-Loop-1: tree-class seed " (pr-str target-id) " should also be queryable verbatim under :tree-class scope")))
      (doseq [{:keys [target-id body]} seeds/all-behavioral-subtree-seeds]
        (is (= body (ontology/get-description ctx :tree-fingerprint target-id))
            (str "Behavioral-subtree seed " (pr-str target-id) " should be queryable verbatim"))))))

(deftest all-seed-recommended-patterns-validate-via-rlm-dsl
  (testing "Every :recommended-pattern / :recommended-alternative snippet either passes rlm-dsl/rlm-dsl->orc-dsl OR uses a documented-unsupported node type (:fallback/:condition)"
    (let [all-seeds (concat seeds/all-node-type-seeds
                            seeds/all-tree-fingerprint-seeds
                            seeds/all-behavioral-subtree-seeds)]
      (doseq [{:keys [target-id body]} all-seeds
              snippet (snippet-strings-from-body body)]
        (let [form (try (read-string snippet)
                        (catch Exception e
                          {::read-error (.getMessage e)}))]
          (cond
            (and (map? form) (::read-error form))
            (is false
                (str "Seed " (pr-str target-id)
                     " has an unparsable snippet — read-string error: "
                     (::read-error form)
                     " — snippet: " snippet))

            (uses-pending-extension? form)
            ;; Documented canonical-only — must NOT throw a different error.
            (is (true? (or (= :fallback (uses-pending-extension? form))
                           (= :condition (uses-pending-extension? form))))
                "uses-pending-extension? should return :fallback or :condition")

            :else
            (is (true? (seeds/snippet-validates? snippet))
                (str "Seed " (pr-str target-id)
                     " has a snippet that fails rlm-dsl/rlm-dsl->orc-dsl: "
                     snippet))))))))
