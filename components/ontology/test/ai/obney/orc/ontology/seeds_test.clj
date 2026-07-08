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

(defn- derive-child-id
  "Recompute the STABLE id the mint-behavioral-subtree command derives for a
   child: nameUUIDFromBytes(\"mint:\" + name + \":\" + parent-behavior). The
   test derives it INDEPENDENTLY from the command so a drift in the command's
   identity derivation is caught (we assert the read-model holds the body at
   THIS id)."
  [name parent-behavior]
  (java.util.UUID/nameUUIDFromBytes
    (.getBytes (str "mint:" name ":" parent-behavior) "UTF-8")))

;; =============================================================================
;; The expected dispatch counts. Each tree-fingerprint seed dual-emits to
;; both :tree-fingerprint and :tree-class scopes (see :tree-class lookup
;; path used by the R-Inject prepend assembler). Behavioral-subtree seeds
;; route through the tree-description command path with :scope
;; :behavioral-subtree in the body — one dispatch each.
;;
;;   10 node-type + 23 tree-fp + 23 tree-class + 12 behavioral
;;   + 5 behavioral-children (E3) = 73
;;
;; If these counts change because someone added a seed, the literal here
;; updates alongside the EDN file content and both tests track the new
;; expected total.
;; =============================================================================

(def ^:private expected-node-type-count 10)
(def ^:private expected-tree-class-count 23)
(def ^:private expected-behavioral-count 12)
(def ^:private expected-behavioral-children-count 5)  ;; E3 (ADR 0014)
(def ^:private expected-total-dispatches
  (+ expected-node-type-count
     expected-tree-class-count   ;; :tree-fingerprint scope
     expected-tree-class-count   ;; :tree-class scope (dual-emit)
     expected-behavioral-count
     expected-behavioral-children-count))  ;; E3 mint-behavioral-subtree

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
                 expected-behavioral-count " behavioral-subtree + "
                 expected-behavioral-children-count " behavioral-children). Got "
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
                   " should round-trip verbatim — body :scope :behavioral-subtree routes it to the behavioral-subtree projector")))
        ;; E3 (ADR 0014): each child round-trips under :tree-fingerprint at its
        ;; DERIVED stable id (the command, not the EDN, owns identity), with the
        ;; mint-stamped :scope + :parent-behavior present.
        (doseq [{:keys [name parent-behavior body]} (:behavioral-subtree-children
                                                      (ontology/baseline-seeds))]
          (let [derived-id (derive-child-id name parent-behavior)
                landed (ontology/get-description ctx :tree-fingerprint derived-id)]
            (is (some? landed)
                (str "Child " (pr-str name) " should round-trip under :tree-fingerprint at its derived id " derived-id))
            (is (= :behavioral-subtree (:scope landed))
                (str "Child " (pr-str name) " landed with :scope :behavioral-subtree"))
            (is (= parent-behavior (:parent-behavior landed))
                (str "Child " (pr-str name) " landed with its :parent-behavior stamped"))
            (is (= (:summary body) (:summary landed))
                (str "Child " (pr-str name) " body :summary round-trips verbatim"))))))))

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

;; =============================================================================
;; E3 (ADR 0014) — durable coding SUBBEHAVIORS: stable id + parent edge
;; =============================================================================

(defn- drive-behavioral-projector!
  "Synchronously drive the R05a behavioral-subtree concept projector over every
   :ontology/tree-description-updated event in the store (mirrors the bench
   runner's drive-projectors!), so the skos:broader edges are deterministically
   present without depending on async pubsub timing in the test."
  [ctx]
  (let [r05a (requiring-resolve
               'ai.obney.orc.ontology.core.todo-processors/on-behavioral-subtree-description-updated-project-concept)]
    (doseq [e (into [] (es/read (:event-store ctx)
                                {:tenant-id (:tenant-id ctx)
                                 :types #{:ontology/tree-description-updated}}))]
      (r05a (assoc ctx :event e)))))

(deftest e3-children-have-stable-derived-ids-not-random
  (testing "E3: each authored child mints at the DERIVED stable id nameUUIDFromBytes(mint:name:parent) — re-running seed-baseline-corpus! lands the SAME id (accrues, never scatters); a random-uuid would produce a new id each run"
    (with-test-ctx [ctx]
      (ontology/seed-baseline-corpus! ctx)
      (ontology/seed-baseline-corpus! ctx)  ;; second run = the re-mint
      (Thread/sleep 200)
      (let [children (:behavioral-subtree-children (ontology/baseline-seeds))]
        (is (= expected-behavioral-children-count (count children))
            "the children resource holds exactly the approved count")
        (doseq [{:keys [name parent-behavior]} children]
          (let [derived-id (derive-child-id name parent-behavior)
                ;; The description read-model holds the CURRENT body at the
                ;; derived id, and the history has TWO entries (one per run) —
                ;; proving both runs resolved to the SAME identity rather than
                ;; scattering to two random ids.
                landed (ontology/get-description ctx :tree-fingerprint derived-id)
                history (ontology/get-description-history ctx :tree-fingerprint derived-id)]
            (is (some? landed)
                (str "Child " (pr-str name) " present at its derived stable id " derived-id))
            (is (= 2 (count history))
                (str "Child " (pr-str name) " accrues at ONE stable id across two runs "
                     "(2 history entries at " derived-id "), not scattered to random ids; got "
                     (count history)))))))))

(deftest e3-children-link-to-parent-via-skos-broader
  (testing "E3: each child is wired to its abstract parent behavior with a skos:broader edge in the concepts graph"
    (with-test-ctx [ctx]
      (ontology/seed-baseline-corpus! ctx)
      (Thread/sleep 200)
      (drive-behavioral-projector! ctx)
      (doseq [{:keys [name parent-behavior]} (:behavioral-subtree-children
                                              (ontology/baseline-seeds))]
        (let [derived-id (derive-child-id name parent-behavior)
              child-uri (str "behavioral-subtree:" derived-id)
              parent-uri (str "behavioral-subtree:" parent-behavior)
              concept (ontology/get-concept-by-uri ctx child-uri)]
          (is (some? concept)
              (str "Child concept exists at " child-uri))
          (is (contains? (:broader concept) parent-uri)
              (str "Child " (pr-str name) " has skos:broader -> " parent-uri
                   "; actual :broader = " (pr-str (:broader concept)))))))))

(deftest e3-children-only-specialize-the-overmatching-parents
  (testing "E3 granularity discipline (RG-2): children specialize ONLY the over-matching parents (Code-building, Transformation, Investigation) — no per-micro-task children, and no child for add-function/refactor/add-endpoint (they share Code-building's shape)"
    (let [children (:behavioral-subtree-children (ontology/baseline-seeds))
          code-building #uuid "bf47c816-2833-320e-9fbd-6ae109275ab0"
          transformation #uuid "86275302-0c3d-35ae-b74e-abd4f27984eb"
          investigation #uuid "760be698-0bb8-3a5a-a2bd-1d45445a5861"
          allowed-parents #{code-building transformation investigation}
          names (set (map :name children))]
      (is (= expected-behavioral-children-count (count children))
          "exactly the approved number of children — granularity sweet spot, not a child per micro-task")
      (is (every? #(contains? allowed-parents (:parent-behavior %)) children)
          "every child specializes one of the three over-matching parents only")
      (is (= #{"code-edit-dependency-wiring" "performance-optimization"
               "documentation-writing" "bug-diagnosis" "rename-move-symbol"}
             names)
          "the authored child names match the approved taxonomy exactly")
      (is (not-any? #{"add-function" "refactor" "add-endpoint"} names)
          "no child for tasks that share Code-building's spec->emit shape (the over-fit trap)"))))

(deftest e3-child-bodies-are-principle-shaped-vectors-of-maps
  (testing "E3: each child's :strengths/:weaknesses are VECTORS OF MAPS (principle-shaped) — bare strings fail schema validation and the mint is dropped silently"
    (doseq [{:keys [name body]} (:behavioral-subtree-children (ontology/baseline-seeds))]
      (is (vector? (:strengths body)) (str name " :strengths is a vector"))
      (is (vector? (:weaknesses body)) (str name " :weaknesses is a vector"))
      (is (every? map? (:strengths body)) (str name " every :strengths entry is a map"))
      (is (every? map? (:weaknesses body)) (str name " every :weaknesses entry is a map"))
      (is (every? :trait (:strengths body)) (str name " every strength has a :trait"))
      (is (every? :good-when (:strengths body)) (str name " every strength has a :good-when guard"))
      (is (every? :avoid-when (:weaknesses body)) (str name " every weakness has an :avoid-when guard")))))
