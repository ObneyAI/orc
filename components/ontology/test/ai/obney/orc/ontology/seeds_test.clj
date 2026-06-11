(ns ai.obney.orc.ontology.seeds-test
  "C-Baseline: the baseline seed corpus ships with the ontology component
   as EDN resources under `components/ontology/resources/seeds/`.

   Consumers of ORC via git deps call `(ontology/seed-baseline-corpus! ctx)`
   on first start to bootstrap the description corpus. The same public path
   is used by the bench runner — there's no longer a parallel dev-only
   loader to keep in sync.

   These tests verify:
   - The public fn exists and emits exactly the expected number of seeds
   - Every seed body is queryable via `get-description` post-load (round-trip)
   - Idempotency: re-running re-emits cleanly without crashing"
  (:require [clojure.test :refer [deftest testing is]]
            [ai.obney.orc.ontology.interface.schemas]
            [ai.obney.orc.ontology.core.commands]
            [ai.obney.orc.ontology.core.read-models]
            [ai.obney.orc.ontology.interface :as ontology]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.query-processor.interface :as qp]
            [ai.obney.grain.pubsub.interface :as pubsub]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]))

;; =============================================================================
;; Test context (mirrors description-events-test pattern)
;; =============================================================================

(defn- create-context []
  (let [ps (pubsub/start {:type :core-async :topic-fn :event/type})
        event-store (es/start {:conn {:type :in-memory} :event-pubsub ps :logger nil})
        cache-dir (str "/tmp/c-baseline-test-" (random-uuid))
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
;; The expected dispatch counts. Each tree-fingerprint seed dual-emits to
;; both :tree-fingerprint and :tree-class scopes (see :tree-class lookup
;; path used by the R-Inject prepend assembler). Behavioral-subtree seeds
;; route through the tree-description command path with :scope
;; :behavioral-subtree in the body — one dispatch each.
;;
;;   10 node-type + 23 tree-fp + 23 tree-class + 12 behavioral = 68
;;
;; If these counts change because someone added a seed, the literal here
;; updates alongside the EDN file content and both tests track the new
;; expected total.
;; =============================================================================

(def ^:private expected-node-type-count 10)
(def ^:private expected-tree-class-count 23)
(def ^:private expected-behavioral-count 12)
(def ^:private expected-total-dispatches
  (+ expected-node-type-count
     expected-tree-class-count   ;; :tree-fingerprint scope
     expected-tree-class-count   ;; :tree-class scope (dual-emit)
     expected-behavioral-count))

(deftest seed-baseline-corpus-exists-and-dispatches-every-seed
  (testing "C-Baseline: the public ontology/seed-baseline-corpus! fn dispatches the right number of seed commands"
    (with-test-ctx [ctx]
      (let [results (ontology/seed-baseline-corpus! ctx)]
        (is (sequential? results)
            "seed-baseline-corpus! returns a sequential of command-results")
        (is (= expected-total-dispatches (count results))
            (str "Expected " expected-total-dispatches
                 " command-results (" expected-node-type-count " node-type + "
                 expected-tree-class-count " tree-fingerprint + "
                 expected-tree-class-count " tree-class dual-emit + "
                 expected-behavioral-count " behavioral-subtree). Got "
                 (count results)))))))

(deftest seeded-bodies-round-trip-via-public-get-description
  (testing "C-Baseline: after seed-baseline-corpus!, every seeded body is queryable verbatim through the public ontology/get-description API"
    (with-test-ctx [ctx]
      (ontology/seed-baseline-corpus! ctx)
      ;; Give read-model projection a moment to land — same approach the
      ;; existing seed-all-emits-everything-and-each-is-queryable test uses.
      (Thread/sleep 200)
      (let [{:keys [node-types tree-classes behavioral-subtrees]}
            (ontology/baseline-seeds)]
        (doseq [{:keys [target-id body]} node-types]
          (is (= body (ontology/get-description ctx :node-type target-id))
              (str "Node-type seed " (pr-str target-id)
                   " should round-trip verbatim through :node-type scope")))
        (doseq [{:keys [target-id body]} tree-classes]
          (is (= body (ontology/get-description ctx :tree-fingerprint target-id))
              (str "Tree-class seed " (pr-str target-id)
                   " should round-trip verbatim under :tree-fingerprint scope"))
          (is (= body (ontology/get-description ctx :tree-class target-id))
              (str "Tree-class seed " (pr-str target-id)
                   " should ALSO round-trip verbatim under :tree-class scope (dual-emit gives the R-Inject prepend assembler a non-nil body from bootstrap)")))
        (doseq [{:keys [target-id body]} behavioral-subtrees]
          (is (= body (ontology/get-description ctx :tree-fingerprint target-id))
              (str "Behavioral-subtree seed " (pr-str target-id)
                   " should round-trip verbatim — body :scope :behavioral-subtree routes it to the behavioral-subtree projector")))))))

(deftest seed-baseline-corpus-is-idempotent
  (testing "C-Baseline: re-running seed-baseline-corpus! is safe — the latest body wins as :current and the history grows by one entry per target"
    (with-test-ctx [ctx]
      (let [first-results (ontology/seed-baseline-corpus! ctx)
            second-results (ontology/seed-baseline-corpus! ctx)]
        (is (= (count first-results) (count second-results))
            "Each run produces the same total dispatch count — no commands are dropped")
        (Thread/sleep 200)
        (let [{:keys [tree-classes]} (ontology/baseline-seeds)
              ;; Pick one representative tree-class — verify its body still
              ;; round-trips identically AND the description history now
              ;; contains 2 entries for it under :tree-class scope (one per
              ;; seed-baseline-corpus! call).
              {:keys [target-id body]} (first tree-classes)]
          (is (= body (ontology/get-description ctx :tree-class target-id))
              "After two seed runs the current body is still the seed body verbatim — re-emission doesn't corrupt content")
          (let [history (ontology/get-description-history ctx :tree-class target-id)]
            (is (= 2 (count history))
                (str "Two seed-baseline-corpus! calls produce 2 history entries under :tree-class for "
                     (pr-str target-id) ", got " (count history)))))))))
