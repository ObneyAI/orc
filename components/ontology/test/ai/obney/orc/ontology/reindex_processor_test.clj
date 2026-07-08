(ns ai.obney.orc.ontology.reindex-processor-test
  "Tests for C-2b-1: re-index processor + retrieval API (pure ColBERT).

   The processor subscribes to :ontology/*-description-updated events.
   When EITHER the configured event-count threshold OR the timer
   threshold crosses, it dispatches colbert-ops/create-index! with the
   current description corpus. Cold-start case (first event when no
   index exists) overrides both thresholds.

   The retrieval API (`search-descriptions`) returns top-K ColBERT
   results with `:document-metadata` carrying granularity + target-id
   + confidence + last-update."
  (:require [clojure.test :refer [deftest testing is]]
            [malli.core :as m]
            [ai.obney.orc.ontology.interface :as ontology]
            [ai.obney.orc.ontology.interface.schemas]
            [ai.obney.orc.ontology.core.commands]
            [ai.obney.orc.ontology.core.read-models]
            [ai.obney.orc.ontology.core.todo-processors :as todo-processors]
            [ai.obney.orc.colbert.interface :as colbert]
            [ai.obney.orc.colbert.interface.schemas]
            [ai.obney.orc.colbert.core.operations :as colbert-ops]
            [ai.obney.grain.event-store-v3.interface.schemas]
            [ai.obney.grain.schema-util.interface :as schema-util]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.query-processor.interface :as qp]
            [ai.obney.grain.pubsub.interface :as pubsub]
            [ai.obney.grain.todo-processor-v2.interface :as tp]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]
            [ai.obney.grain.time.interface :as time]))

;; =============================================================================
;; Test context helpers
;; =============================================================================

(defn- create-context []
  (let [ps (pubsub/start {:type :core-async :topic-fn :event/type})
        event-store (es/start {:conn {:type :in-memory} :event-pubsub ps :logger nil})
        cache-dir (str "/tmp/c2b1-test-" (random-uuid))
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

;; =============================================================================
;; RED #1 — :ontology/reindex-config-set event schema validates
;; =============================================================================

(deftest reindex-config-set-event-schema-validates
  (testing "A well-formed :ontology/reindex-config-set event passes its schema"
    (let [event {:reindex-threshold-events 10
                 :reindex-timer-minutes 5
                 :set-at "2026-05-26T00:00:00Z"}
          schema (get @schema-util/registry* :ontology/reindex-config-set)]
      (is (some? schema)
          ":ontology/reindex-config-set schema should be registered")
      (is (m/validate schema event)
          (str "Event should validate. Explanation: "
               (when schema (pr-str (m/explain schema event))))))))

;; =============================================================================
;; RED #2 — :ontology/set-reindex-config command emits matching event
;; =============================================================================

(deftest set-reindex-config-command-emits-event
  (testing "Dispatching :ontology/set-reindex-config emits :ontology/reindex-config-set with the configured values"
    (with-test-ctx [ctx]
      (let [cmd {:command/name :ontology/set-reindex-config
                 :command/id (random-uuid)
                 :command/timestamp (time/now)
                 :reindex-threshold-events 25
                 :reindex-timer-minutes 10}
            result (cp/process-command (assoc ctx :command cmd))
            event (first (:command-result/events result))]
        (is (= :ontology/reindex-config-set (:event/type event))
            "Event type is :ontology/reindex-config-set")
        (is (= 25 (:reindex-threshold-events event))
            "Event carries :reindex-threshold-events from the command")
        (is (= 10 (:reindex-timer-minutes event))
            "Event carries :reindex-timer-minutes from the command")
        (is (string? (:set-at event))
            "Event stamps :set-at")))))

;; =============================================================================
;; RED #3 + #4 — get-reindex-config returns defaults when unset / set values when set
;; =============================================================================

(deftest get-reindex-config-defaults-when-unset
  (testing "get-reindex-config returns {N=10, T=5} when no event has been emitted"
    (with-test-ctx [ctx]
      (let [cfg (ontology/get-reindex-config ctx)]
        (is (= 10 (:reindex-threshold-events cfg))
            "Default :reindex-threshold-events is 10")
        (is (= 5 (:reindex-timer-minutes cfg))
            "Default :reindex-timer-minutes is 5")))))

(deftest get-reindex-config-returns-set-values
  (testing "After set-reindex-config command, get-reindex-config reflects the values"
    (with-test-ctx [ctx]
      (cp/process-command
        (assoc ctx :command
               {:command/name :ontology/set-reindex-config
                :command/id (random-uuid)
                :command/timestamp (time/now)
                :reindex-threshold-events 50
                :reindex-timer-minutes 15}))
      (Thread/sleep 100)
      (let [cfg (ontology/get-reindex-config ctx)]
        (is (= 50 (:reindex-threshold-events cfg)))
        (is (= 15 (:reindex-timer-minutes cfg)))))))

;; =============================================================================
;; RED #5 — reindex-state read-model tracks events-since-last-rebuild
;; =============================================================================

(defn- emit-description-updated! [ctx target-type target-id]
  (let [cmd-name (case target-type
                   :node-type        :ontology/record-node-type-description
                   :node-instance    :ontology/record-node-instance-description
                   :tree-fingerprint :ontology/record-tree-description)
        body {:capabilities ["x"]
              :strengths    [{:trait "t" :good-when "ctx"
                              :recommended-pattern "[:llm {...}]"
                              :confidence 0.9 :evidence-count 1
                              :first-observed-at "2026-05-26T00:00:00Z"
                              :last-reinforced-at "2026-05-26T00:00:00Z"}]
              :weaknesses   []
              :representative-uses ["x"]
              :avoid-when   ["x"]
              :summary      (str "Summary for " target-id)
              :version 1
              :consolidated-from-event-count 1}]
    (cp/process-command
      (assoc ctx :command
             {:command/name cmd-name
              :command/id (random-uuid)
              :command/timestamp (time/now)
              :target-id target-id
              :body body}))))

(deftest reindex-state-tracks-events-since-last-rebuild
  (testing "After 3 description-updated events, reindex-state events-since-last-rebuild is 3 and :index-built? is false"
    (with-test-ctx [ctx]
      (emit-description-updated! ctx :node-type :llm)
      (emit-description-updated! ctx :node-type :code)
      (emit-description-updated! ctx :node-type :map-each)
      (Thread/sleep 200)
      (let [state (ontology/get-reindex-state ctx)]
        (is (= 3 (:events-since-last-rebuild state))
            "Counter is 3 after 3 description-updated events across granularities")
        (is (= false (:index-built? state))
            ":index-built? remains false until a :colbert/index-created event lands")))))

;; =============================================================================
;; RED #6 — reindex-state resets on :colbert/index-created
;; =============================================================================

(defn- inject-index-created!
  "Append a :colbert/index-created event for the ontology-descriptions
   index directly to the event store. The body satisfies colbert's
   event schema (which requires the full source-data set for
   regeneration). Used by tests to verify the reindex-state read-model's
   reset behavior without spinning up ColBERT itself."
  [ctx]
  (es/append (:event-store ctx)
    {:tenant-id (:tenant-id ctx)
     :events [(es/->event
                {:type :colbert/index-created
                 :tags #{}
                 :body {:index-id (random-uuid)
                        :index-name "ontology-descriptions"
                        :index-path "/tmp/test-index"
                        :documents ["doc-1"]
                        :document-ids ["id-1"]
                        :document-count 1
                        :passage-count 1
                        :model-name "colbert-ir/colbertv2.0"
                        :config {:split-documents? true
                                 :max-document-length 256
                                 :use-faiss? false}
                        :created-at "2026-05-26T00:00:00Z"}})]}))

(deftest reindex-state-resets-on-colbert-index-created
  (testing "After :colbert/index-created (for ontology-descriptions) lands, counter resets to 0 and :index-built? flips to true"
    (with-test-ctx [ctx]
      (emit-description-updated! ctx :node-type :llm)
      (emit-description-updated! ctx :node-type :code)
      (Thread/sleep 100)
      (is (= 2 (:events-since-last-rebuild (ontology/get-reindex-state ctx)))
          "Sanity: counter is 2 before the index-created event")
      (inject-index-created! ctx)
      (Thread/sleep 100)
      (let [state (ontology/get-reindex-state ctx)]
        (is (= 0 (:events-since-last-rebuild state))
            "Counter resets to 0")
        (is (= true (:index-built? state))
            ":index-built? flips to true")
        (is (string? (:last-rebuild-timestamp state))
            ":last-rebuild-timestamp captured as a string")))))

;; =============================================================================
;; Re-index trigger helpers — used by RED #7/#8/#9/#10
;;
;; ColBERT itself is heavy (spawns a Python subprocess). The processor under
;; test is supposed to *dispatch* colbert-ops/create-index! and let ColBERT do its
;; thing on its own thread. For unit tests, we redef create-index! to a stub
;; that captures the call args + emits a real :colbert/index-created event
;; (so the read-model counter actually resets).
;; =============================================================================

(defn- stub-create-index!
  "Returns a [calls-atom stub-fn] pair. Use:
     (with-redefs [colbert-ops/create-index! stub-fn] ...)

   We redef the operations-layer fn (not the interface fn). The
   processor dispatches the `:colbert/create-index` defcommand via
   the command processor; the defcommand calls
   operations/create-index! internally to talk to the bridge, then
   emits :colbert/index-created based on the returned map. By
   stubbing operations-level, the defcommand still runs and emits a
   real event — we just skip the actual Python subprocess.

   The atom captures every call's opts map. The stub returns the
   shape the defcommand needs to emit a schema-valid event."
  []
  (let [calls (atom [])]
    [calls
     (fn [_ctx opts]
       (swap! calls conj opts)
       (let [index-id (random-uuid)
             collection (:collection opts)
             docs (vec collection)
             doc-ids (or (:document-ids opts)
                         (vec (map str (range (count docs)))))
             docs-metadatas (or (:document-metadatas opts) [])]
         {:index-id index-id
          :index-path (str "/tmp/test-stub-" index-id)
          :num-passages (count docs)
          :duration-ms 1
          :document-ids doc-ids
          :document-metadatas docs-metadatas
          :document-count (count docs)
          :model-name (or (:model-name opts) "colbert-ir/colbertv2.0")
          :index-name (:index-name opts)
          :config {:split-documents? (boolean (get opts :split-documents? true))
                   :max-document-length (or (:max-document-length opts) 256)
                   :use-faiss? false}}))]))

;; =============================================================================
;; RED #7 — Re-index processor triggers create-index! at threshold N
;; =============================================================================

;; =============================================================================
;; RED #13 — search-descriptions with :granularity filter
;; =============================================================================

(deftest search-descriptions-filters-by-granularity
  (testing "When :granularity is supplied, only results with that granularity in :document-metadata are returned"
    (with-test-ctx [ctx]
      (let [mixed-results [{:content "node-type doc"
                            :score 0.9 :rank 1
                            :document-id "node-type::validate"
                            :document-metadata {:granularity :node-type
                                                :target-id :validate
                                                :confidence 0.8
                                                :last-update "2026-05-26T10:00:00Z"}}
                           {:content "tree doc 1"
                            :score 0.85 :rank 2
                            :document-id "tree-fingerprint::abc"
                            :document-metadata {:granularity :tree-fingerprint
                                                :target-id "abc"
                                                :confidence 0.75
                                                :last-update "2026-05-26T10:00:00Z"}}
                           {:content "node-instance doc"
                            :score 0.7 :rank 3
                            :document-id "node-instance::xyz"
                            :document-metadata {:granularity :node-instance
                                                :target-id ["sheet-1" "node-1"]
                                                :confidence 0.6
                                                :last-update "2026-05-26T10:00:00Z"}}
                           {:content "tree doc 2"
                            :score 0.65 :rank 4
                            :document-id "tree-fingerprint::def"
                            :document-metadata {:granularity :tree-fingerprint
                                                :target-id "def"
                                                :confidence 0.5
                                                :last-update "2026-05-26T10:00:00Z"}}]]
        (inject-index-created! ctx)
        (Thread/sleep 100)
        (with-redefs [colbert/search (fn [_ctx _opts] mixed-results)]
          (let [tree-only (ontology/search-descriptions ctx
                            {:query "tree pattern"
                             :granularity :tree-fingerprint
                             :k 10})]
            (is (= 2 (count tree-only))
                "Only the 2 tree-fingerprint results survive the filter")
            (is (every? #(= :tree-fingerprint (-> % :document-metadata :granularity))
                        tree-only)
                "Every returned result has :granularity = :tree-fingerprint"))
          (let [node-type-only (ontology/search-descriptions ctx
                                 {:query "node pattern"
                                  :granularity :node-type
                                  :k 10})]
            (is (= 1 (count node-type-only))
                "Only the 1 node-type result survives the filter"))
          (let [all (ontology/search-descriptions ctx
                      {:query "anything"
                       :granularity :all
                       :k 10})]
            (is (= 4 (count all))
                ":all returns every result without filtering")))))))

;; =============================================================================
;; EL-1a RED — search-descriptions accepts a granularity SET (multi-axis)
;;
;; The scatter bug: classify-task queried ONLY :tree-fingerprint, and
;; search-descriptions hard-filtered to a single granularity — so a
;; recorded :tree-class description was indexed-but-UNREACHABLE. The fix
;; lets :granularity be a SET so a caller can retrieve across both the
;; :tree-fingerprint and :tree-class axes in one query (membership filter,
;; no :all node-type/node-instance noise). This is the deterministic merge
;; surface; the live real-ColBERT proof lives in the EL-1a prototype.
;; =============================================================================

(deftest search-descriptions-accepts-granularity-set
  (testing "When :granularity is a SET, results whose granularity is in the set survive; others are filtered out"
    (with-test-ctx [ctx]
      (let [tree-class-id (random-uuid)
            mixed-results [{:content "node-type doc"
                            :score 0.95 :rank 1
                            :document-id "node-type::validate"
                            :document-metadata {:granularity :node-type
                                                :target-id :validate
                                                :confidence 0.8
                                                :last-update "2026-06-01T10:00:00Z"}}
                           {:content "tree-fingerprint doc"
                            :score 0.9 :rank 2
                            :document-id "tree-fingerprint::abc"
                            :document-metadata {:granularity :tree-fingerprint
                                                :target-id "abc"
                                                :confidence 0.75
                                                :last-update "2026-06-01T10:00:00Z"}}
                           {:content "tree-class doc (the previously-unreachable one)"
                            :score 0.85 :rank 3
                            :document-id (str "tree-class::" tree-class-id)
                            :document-metadata {:granularity :tree-class
                                                :target-id tree-class-id
                                                :confidence 0.7
                                                :last-update "2026-06-01T10:00:00Z"}}
                           {:content "node-instance doc"
                            :score 0.6 :rank 4
                            :document-id "node-instance::xyz"
                            :document-metadata {:granularity :node-instance
                                                :target-id ["sheet-1" "node-1"]
                                                :confidence 0.5
                                                :last-update "2026-06-01T10:00:00Z"}}]]
        (inject-index-created! ctx)
        (Thread/sleep 100)
        (with-redefs [colbert/search (fn [_ctx _opts] mixed-results)]
          (let [two-axis (ontology/search-descriptions ctx
                           {:query "wire a dependency"
                            :granularity #{:tree-fingerprint :tree-class}
                            :k 10})
                grans (set (map #(-> % :document-metadata :granularity) two-axis))
                target-ids (set (map #(-> % :document-metadata :target-id) two-axis))]
            (is (= 2 (count two-axis))
                "Both the :tree-fingerprint AND :tree-class docs survive the set filter")
            (is (= #{:tree-fingerprint :tree-class} grans)
                "Only the two requested axes are present (no node-type/node-instance noise)")
            (is (contains? target-ids tree-class-id)
                "The :tree-class doc — indexed-but-unreachable under single-axis filtering — is now RETRIEVABLE")
            (is (not (contains? grans :node-type))
                ":node-type noise is excluded")
            (is (not (contains? grans :node-instance))
                ":node-instance noise is excluded"))
          ;; Regression: a single-keyword granularity still behaves exactly as before.
          (let [tf-only (ontology/search-descriptions ctx
                          {:query "tree pattern"
                           :granularity :tree-fingerprint
                           :k 10})]
            (is (= 1 (count tf-only))
                "Single-keyword :tree-fingerprint still returns only the tree-fingerprint doc (no regression)")
            (is (= :tree-fingerprint (-> (first tf-only) :document-metadata :granularity))
                "And it is the tree-fingerprint doc")))))))

;; =============================================================================
;; RED #12 — search-descriptions returns ColBERT results with :document-metadata
;; =============================================================================

(deftest search-descriptions-returns-results-with-metadata
  (testing "When an index exists, search-descriptions returns ColBERT hits each carrying :document-metadata with :granularity, :target-id, :confidence, :last-update"
    (with-test-ctx [ctx]
      (let [fake-results [{:content "validation loop pattern summary"
                           :score 0.92
                           :rank 1
                           :document-id "node-type::validate"
                           :document-metadata {:granularity :node-type
                                               :target-id :validate
                                               :confidence 0.85
                                               :last-update "2026-05-26T10:00:00Z"}}
                          {:content "chunked extraction tree summary"
                           :score 0.78
                           :rank 2
                           :document-id "tree-fingerprint::chunked-extraction"
                           :document-metadata {:granularity :tree-fingerprint
                                               :target-id "chunked-extraction"
                                               :confidence 0.7
                                               :last-update "2026-05-26T11:00:00Z"}}]]
        (inject-index-created! ctx)
        (Thread/sleep 100)
        (with-redefs [colbert/search (fn [_ctx _opts] fake-results)]
          (let [results (ontology/search-descriptions ctx
                          {:query "validation loop" :k 5})]
            (is (= 2 (count results))
                "Returns the 2 stubbed ColBERT hits")
            (let [first-result (first results)]
              (is (string? (:content first-result))
                  "Each result carries :content")
              (is (number? (:score first-result))
                  "Each result carries :score")
              (let [md (:document-metadata first-result)]
                (is (= :node-type (:granularity md))
                    ":document-metadata.:granularity is set")
                (is (= :validate (:target-id md))
                    ":document-metadata.:target-id is set")
                (is (number? (:confidence md))
                    ":document-metadata.:confidence is a number")
                (is (string? (:last-update md))
                    ":document-metadata.:last-update is a string")))))))))

;; =============================================================================
;; RED #11 — search-descriptions returns empty + log when no index exists
;; =============================================================================

(deftest search-descriptions-empty-on-cold-search
  (testing "Search with no ontology-descriptions index yet returns [] and never spawns a synchronous rebuild"
    (with-test-ctx [ctx]
      (let [results (ontology/search-descriptions ctx {:query "validation loop pattern"})]
        (is (vector? results)
            "Returns a vector (not nil)")
        (is (empty? results)
            "Empty vector when no index has been built")))))

;; =============================================================================
;; RED #10 — Bootstrap-on-startup: ontology/bootstrap-reindex! rebuilds when descriptions exist + no index
;; =============================================================================

(deftest bootstrap-reindex-rebuilds-when-descriptions-exist
  (testing "When descriptions are already in the event store but no index has been built, bootstrap-reindex! triggers a rebuild"
    (with-test-ctx [ctx]
      ;; Seed descriptions WITHOUT running the processor's reaction. We
      ;; emit them BEFORE installing the with-redefs stub, simulating a
      ;; restart scenario where description events already exist in the
      ;; event store but the index has never been built. The processor
      ;; will fire its cold-start path on each of these — to match a
      ;; clean-restart scenario, we then inject a no-op index-created so
      ;; index-built? is true (i.e., index was built in a prior run, then
      ;; lost), then DELETE it... but simpler: just verify bootstrap is a
      ;; no-op when index is built, separately from rebuild case.
      (let [[calls stub] (stub-create-index!)]
        (with-redefs [colbert-ops/create-index! stub]
          ;; Seed two descriptions — each triggers the processor's
          ;; cold-start path because index-built? is initially false,
          ;; producing rebuild call(s). After they settle, index-built?
          ;; is true.
          (emit-description-updated! ctx :node-type :validate)
          (emit-description-updated! ctx :node-type :map-each)
          (Thread/sleep 300)
          (let [calls-before (count @calls)]
            (is (pos? calls-before)
                "Sanity: cold-start path fired during seed phase")
            (is (true? (:index-built? (ontology/get-reindex-state ctx)))
                "Sanity: index-built? is true after cold-start rebuilds")
            ;; Now: with index-built? already true and counter at 0
            ;; (reset by the rebuilds), bootstrap should be a no-op.
            (ontology/bootstrap-reindex! ctx)
            (is (= calls-before (count @calls))
                "Bootstrap is a no-op when an index already exists and no events accumulated")))))))

(deftest bootstrap-reindex-rebuilds-cold-corpus
  (testing "When the processor was never wired in (synthetic 'lost wiring' scenario), bootstrap-reindex! still rebuilds from a non-empty descriptions corpus + no index"
    (with-test-ctx [ctx]
      ;; Seed descriptions WITHOUT the redef stub installed → real
      ;; colbert-ops/create-index! gets called by the processor's cold-start
      ;; path. Since we don't want to actually spawn ColBERT in a unit
      ;; test, we instead emit raw description events via the command
      ;; processor but stub create-index! to a no-op atom-recorder that
      ;; does NOT emit :colbert/index-created. This simulates "the
      ;; create-index call happened but the event never landed" —
      ;; equivalent to a fresh-startup with descriptions but no index.
      (let [calls (atom [])
            ;; Silent stub: records the call but throws so the
            ;; :colbert/create-index defcommand's catch block returns
            ;; an anomaly instead of emitting an :colbert/index-created
            ;; event. This simulates "create-index was attempted but
            ;; the index never landed" — equivalent to a fresh startup
            ;; with descriptions but no index, so bootstrap-reindex!
            ;; should still see :index-built? false.
            silent-stub (fn [_ctx opts]
                          (swap! calls conj opts)
                          (throw (ex-info "silent stub" {})))]
        (with-redefs [colbert-ops/create-index! silent-stub]
          (emit-description-updated! ctx :node-type :validate)
          (Thread/sleep 200)
          (reset! calls [])  ;; clear processor's cold-start call
          (is (false? (:index-built? (ontology/get-reindex-state ctx)))
              "Sanity: no :colbert/index-created event was emitted, so :index-built? remains false")
          (ontology/bootstrap-reindex! ctx)
          (is (= 1 (count @calls))
              "bootstrap-reindex! triggers one create-index! call because descriptions exist + no index built"))))))

;; =============================================================================
;; RED #9 — Cold-start case: first description-updated event triggers immediate rebuild
;; =============================================================================

(deftest reindex-processor-cold-start-on-first-event
  (testing "With no index ever built, the first description-updated event triggers an immediate rebuild even though counter is 1 (< threshold 10) and no time has elapsed"
    (with-test-ctx [ctx]
      (let [[calls stub] (stub-create-index!)]
        (with-redefs [colbert-ops/create-index! stub]
          (is (false? (:index-built? (ontology/get-reindex-state ctx)))
              "Sanity: no index built yet")
          (emit-description-updated! ctx :node-type :validate)
          (Thread/sleep 300)
          (is (= 1 (count @calls))
              "create-index! is called once on the first event because :index-built? was false (cold-start path)")
          (is (true? (:index-built? (ontology/get-reindex-state ctx)))
              "After cold-start rebuild, :index-built? is true"))))))

;; =============================================================================
;; RED #8 — Re-index processor triggers create-index! at timer T (faked clock)
;; =============================================================================

(deftest reindex-processor-fires-at-timer
  (testing "When fewer-than-N events have accumulated but the timer threshold elapsed, the processor rebuilds. Uses with-redefs on minutes-since to fake elapsed time without sleeping."
    (with-test-ctx [ctx]
      ;; Pre-seed an index so we are testing timer-trigger, not cold-start.
      (inject-index-created! ctx)
      (Thread/sleep 100)
      (let [[calls stub] (stub-create-index!)]
        (with-redefs [colbert-ops/create-index! stub
                      ;; Fake: pretend 6 minutes have elapsed (> default 5)
                      todo-processors/minutes-since (fn [_iso-str] 6)]
          ;; Only 1 event — well below threshold 10
          (emit-description-updated! ctx :node-type :map-each)
          (Thread/sleep 300)
          (is (= 1 (count @calls))
              "create-index! is called once because the timer threshold was crossed even though only 1 event accumulated")
          (let [opts (first @calls)]
            (is (= "ontology-descriptions" (:index-name opts))
                "Stable index-name is used")))))))

(deftest reindex-processor-fires-at-threshold
  (testing "After N description-updated events accumulate post-bootstrap (N=default 10), the processor fires colbert-ops/create-index! exactly once and the counter resets to 0"
    (with-test-ctx [ctx]
      ;; Pre-seed an index so the cold-start path is satisfied. We are
      ;; testing the steady-state threshold trigger (cold-start has its
      ;; own RED below).
      (inject-index-created! ctx)
      (Thread/sleep 100)
      (is (true? (:index-built? (ontology/get-reindex-state ctx)))
          "Sanity: index is built before we test threshold trigger")
      (let [[calls stub] (stub-create-index!)]
        (with-redefs [colbert-ops/create-index! stub]
          ;; threshold defaults to 10 — emit 10 description-updated events
          (dotimes [i 10]
            (emit-description-updated! ctx :node-type
              (keyword (str "node-type-" i))))
          (Thread/sleep 500)
          (is (= 1 (count @calls))
              "create-index! is called exactly once after 10 events")
          (let [opts (first @calls)]
            (is (= "ontology-descriptions" (:index-name opts))
                "Stable index-name is used")
            (is (sequential? (:collection opts))
                ":collection is a vector of document strings"))
          (is (= 0 (:events-since-last-rebuild (ontology/get-reindex-state ctx)))
              "Counter resets to 0 after the rebuild lands"))))))

;; =============================================================================
;; QP-3 — Behavioral-subtree mint triggers an IMMEDIATE re-index
;;        (bypasses the threshold-10 gate)
;; =============================================================================
;;
;; Mints are low-frequency, authoritative contributions: one mint per agent-
;; recognized novel-task. The R-Inject prepend tells the agent its mint will
;; be retrievable on subsequent classify-behaviors calls. The threshold-10
;; gate breaks that promise — a single mint waits for 9 unrelated description
;; events to accumulate before the corpus rebuilds. Fix: a separate processor
;; subscribed to :ontology/behavioral-subtree-minted that forces a rebuild
;; immediately, independent of events-since-last-rebuild.

(defn- dispatch-mint!
  "Dispatch :ontology/mint-behavioral-subtree (human-authored to keep the
   command body simple — no sandbox provenance fields)."
  [ctx behavior-name]
  (cp/process-command
    (assoc ctx :command
           {:command/name :ontology/mint-behavioral-subtree
            :command/id (random-uuid)
            :command/timestamp (time/now)
            :name behavior-name
            :body {:capabilities ["x"]
                   :strengths [{:trait "t" :good-when "ctx"
                                :recommended-pattern "[:llm {...}]"
                                :confidence 0.9 :evidence-count 1
                                :first-observed-at "2026-06-08T00:00:00Z"
                                :last-reinforced-at "2026-06-08T00:00:00Z"}]
                   :weaknesses []
                   :representative-uses ["x"]
                   :avoid-when ["x"]
                   :summary (str "Summary for " behavior-name)
                   :version 1
                   :consolidated-from-event-count 0}
            :provenance :human-authored})))

(deftest mint-triggers-immediate-rebuild-bypassing-threshold
  (testing "QP-3: A single :ontology/behavioral-subtree-minted event triggers create-index! immediately even when events-since-last-rebuild is < threshold"
    (with-test-ctx [ctx]
      ;; Pre-seed the index so cold-start is satisfied — we want to verify
      ;; the FORCED rebuild path, not the cold-start path that's already
      ;; covered by reindex-processor-cold-start-on-first-event.
      (inject-index-created! ctx)
      (Thread/sleep 100)
      (is (true? (:index-built? (ontology/get-reindex-state ctx)))
          "Sanity: index is built before we test the forced rebuild")
      (is (= 0 (:events-since-last-rebuild (ontology/get-reindex-state ctx)))
          "Sanity: counter is 0 before the mint")
      (let [[calls stub] (stub-create-index!)]
        (with-redefs [colbert-ops/create-index! stub]
          (dispatch-mint! ctx "qp3-test-behavior")
          (Thread/sleep 500)
          (is (pos? (count @calls))
              (str "QP-3 unfixed: a single mint should trigger a rebuild even "
                   "though only 1 description event accumulated (well under "
                   "threshold 10). Without the fix, create-index! is never "
                   "called and the minted behavior stays invisible to "
                   "classify-behaviors until 9 more description events land."))
          (let [opts (first @calls)
                target-ids (->> (:document-metadatas opts) (map :target-id) set)]
            (is (= "ontology-descriptions" (:index-name opts))
                "Same index-name as the threshold-path rebuild")
            (is (some? opts)
                "rebuild opts captured")
            ;; The minted target-id is derived from name + parent-behavior
            ;; via nameUUIDFromBytes, so we know it deterministically and
            ;; can assert the minted doc is IN the rebuilt corpus.
            (let [expected-target-id (java.util.UUID/nameUUIDFromBytes
                                       (.getBytes (str "mint:qp3-test-behavior:" nil)
                                                  "UTF-8"))]
              (is (contains? target-ids expected-target-id)
                  (str "The minted behavior's target-id should be in the rebuilt "
                       "corpus. Expected " expected-target-id
                       " among " target-ids)))))))))
