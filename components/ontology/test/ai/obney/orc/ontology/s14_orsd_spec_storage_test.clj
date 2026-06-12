(ns ai.obney.orc.ontology.s14-orsd-spec-storage-test
  "S14 — ORSD spec storage acceptance tests.

   The ontology's requirements specification (ORSD) — purpose, scope,
   intended-uses, competency-questions, natural-language-statements,
   non-functional — is persisted as an event-sourced contract that
   mirrors the descriptions-event pattern shape-for-shape.

   Each deftest corresponds to one acceptance criterion from the slice:

   1. Command → event → projection round-trip; current spec and the
      full revision history are queryable per ontology-id via the
      public interface (`ontology/get-ontology-spec`,
      `ontology/get-ontology-spec-history`).
   2. Spec revisions NEVER destroy history. Adversarial fixture:
      record THREE revisions for one ontology-id; assert all three
      are retrievable in chronological order; assert the `current`
      accessor returns the THIRD revision body; assert the event
      store actually carries three distinct events (not one mutated
      payload).
   3. Malli schema validates the ORSD shape and unknown extra top-
      level keys are REJECTED loudly via `cp/process-command`. The
      anomaly returned MUST carry `::anom/category ::anom/incorrect`
      — the same shape downstream consumer code receives for any
      schema-rejected command. Belt+suspenders: NO event lands in
      the event store; NO projection state mutates.
   4. All ORSD fields are optional. A command carrying only
      `:competency-questions` succeeds and projects.
   5. Sparse-spec back-compat: components depending on the existing
      ontology-metadata + descriptions projections continue to work
      with no spec ever recorded for that ontology-id (regression).

   Live verification path: real Grain in-memory event store; commands
   dispatched through `cp/process-command` (so the Malli schema gate
   actually fires); `rmp/project` reads from the descriptions and
   ontology-specs projections; public-interface accessors surface
   the results."
  (:require [clojure.test :refer [deftest testing is]]
            ;; Event + command schema registration — required so the
            ;; Grain command-processor's pre-handler Malli gate and the
            ;; event-store's append-time Malli validation see the new
            ;; schemas.
            [ai.obney.orc.ontology.interface.schemas]
            [ai.obney.orc.ontology.core.commands]
            [ai.obney.orc.ontology.core.read-models]
            [ai.obney.orc.ontology.interface :as ontology]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.query-processor.interface :as qp]
            [ai.obney.grain.pubsub.interface :as pubsub]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp]
            [ai.obney.grain.time.interface :as time]
            [cognitect.anomalies :as anom]))

;; =============================================================================
;; Test context — same in-memory pattern the descriptions tests use so the
;; Malli command schema gate fires through cp/process-command.
;; =============================================================================

(defn- create-context []
  (rmp/l1-clear!)
  (let [ps (pubsub/start {:type :core-async :topic-fn :event/type})
        event-store (es/start {:conn {:type :in-memory} :event-pubsub ps :logger nil})
        cache-dir (str "/tmp/s14-test-" (random-uuid))
        cache (kv/start (lmdb/->KV-Store-LMDB {:storage-dir cache-dir :db-name "test"}))]
    {:event-store event-store
     :cache cache
     :tenant-id (random-uuid)
     :command-registry (cp/global-command-registry)
     :query-registry (qp/global-query-registry)
     :event-pubsub ps
     ::cache-dir cache-dir}))

(defn- stop-context [ctx]
  (rmp/l1-clear!)
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

(defn- record-spec! [ctx ontology-id body]
  (cp/process-command
   (assoc ctx :command
          {:command/name :ontology/record-ontology-spec
           :command/id (random-uuid)
           :command/timestamp (time/now)
           :ontology-id ontology-id
           :body body})))

;; =============================================================================
;; Fixture — a minimal but well-formed ORSD body
;; =============================================================================

(def sample-spec-v1
  "First revision — purpose + scope + two CQs. Real specs grow from
   here; the v2/v3 fixtures below demonstrate the grow trajectory."
  {:purpose
   "Capture the workshop's structured retrieval requirements so the
    discovery and build phases can target a knowable contract."
   :scope
   "Workshop ontology only — failure, success, problem-domain layers."
   :intended-uses
   ["Drive R-Inject's pattern retrieval"
    "Ground the CQ evaluator (S15)"]
   :competency-questions
   ["Which trees solve hallucination failures?"
    "What are the top three problem categories the corpus covers?"]
   :natural-language-statements
   ["Every concept has a stable URI and a label."]
   :non-functional
   {:retrieval-latency-budget-ms 250
    :index-rebuild-cadence "5 minutes"}})

(def sample-spec-v2
  "Second revision — broader purpose, additional CQ, new NL statement."
  (-> sample-spec-v1
      (assoc :purpose
             "Capture both retrieval AND evaluation contracts for the workshop ontology.")
      (update :competency-questions conj
              "Which failures lack a recommended-alternative entry?")
      (update :natural-language-statements conj
              "Every concept's broader URIs are themselves concepts in the same ontology.")))

(def sample-spec-v3
  "Third revision — adds intended-use entry and tightens the latency
   budget. Demonstrates that v3's :current must replace v2 while v1
   and v2 remain in history."
  (-> sample-spec-v2
      (update :intended-uses conj "Surface the orientation card (S20)")
      (assoc-in [:non-functional :retrieval-latency-budget-ms] 200)))

;; =============================================================================
;; AC1 — Command → event → projection round-trip
;; =============================================================================

(deftest record-ontology-spec-emits-event-and-projects-current
  (testing "Dispatching :ontology/record-ontology-spec emits ONE
            :ontology/ontology-spec-recorded event; the projection
            carries the body as :current; the public-interface query
            returns the recorded body."
    (with-test-ctx [ctx]
      (let [ontology-id (random-uuid)
            result (record-spec! ctx ontology-id sample-spec-v1)]
        (is (not (some? (::anom/category result)))
            (str "Command must succeed for a well-formed spec — got "
                 (::anom/category result) " : " (::anom/message result)
                 " : " (:error/explain result)))
        (let [events (:command-result/events result)]
          (is (= 1 (count events))
              "Exactly one event per command")
          (let [e (first events)]
            (is (= :ontology/ontology-spec-recorded (:event/type e)))
            (is (= ontology-id (:ontology-id e)))
            (is (= sample-spec-v1 (:body e))
                "Event body carries the spec verbatim")
            (is (some? (:recorded-at e))
                ":recorded-at timestamp stamped on the event")))
        (Thread/sleep 100)
        (is (= sample-spec-v1 (ontology/get-ontology-spec ctx ontology-id))
            "get-ontology-spec returns the recorded body")
        (let [history (ontology/get-ontology-spec-history ctx ontology-id)]
          (is (= 1 (count history))
              "History carries the single revision")
          (is (= sample-spec-v1 (:body (first history)))
              "History entry body matches the recorded spec")
          (is (some? (:recorded-at (first history)))
              "History entry carries :recorded-at"))))))

;; =============================================================================
;; AC2 — Spec revisions NEVER destroy history (adversarial)
;; =============================================================================

(deftest three-revisions-preserve-full-history-in-chronological-order
  (testing "Recording v1, v2, v3 for one ontology-id: :current returns
            v3; :history returns [v1 v2 v3] in chronological order;
            the event store carries THREE distinct events (not one
            mutated payload)."
    (with-test-ctx [ctx]
      (let [ontology-id (random-uuid)]
        (record-spec! ctx ontology-id sample-spec-v1)
        ;; Sleep between recordings so the recorded-at timestamps
        ;; are strictly monotonic and the ordering is observable.
        (Thread/sleep 10)
        (record-spec! ctx ontology-id sample-spec-v2)
        (Thread/sleep 10)
        (record-spec! ctx ontology-id sample-spec-v3)
        (Thread/sleep 100)

        (is (= sample-spec-v3
               (ontology/get-ontology-spec ctx ontology-id))
            ":current returns the THIRD revision body")

        (let [history (ontology/get-ontology-spec-history ctx ontology-id)]
          (is (= 3 (count history))
              "History carries all three revisions — none destroyed")
          (is (= [sample-spec-v1 sample-spec-v2 sample-spec-v3]
                 (mapv :body history))
              "History bodies are in chronological order")
          (let [timestamps (map :recorded-at history)]
            (is (every? some? timestamps)
                "Every history entry carries :recorded-at")
            (is (apply distinct? timestamps)
                "Revision timestamps are distinct (no mutated-in-place
                 single event masquerading as three)")))

        ;; Belt+suspenders — read the event store directly and confirm
        ;; the count of :ontology/ontology-spec-recorded events is
        ;; three. This catches a (hypothetical) bug where the projection
        ;; double-counts a single event.
        (let [events (->> (es/read (:event-store ctx)
                                   {:tenant-id (:tenant-id ctx)
                                    :types #{:ontology/ontology-spec-recorded}})
                          (into []))]
          (is (= 3 (count events))
              "Event store carries three distinct events for the spec
               — confirms append-only history was actually written"))))))

;; =============================================================================
;; AC3 — Unknown extra top-level keys are REJECTED loudly
;; =============================================================================

(deftest unknown-extra-keys-rejected-with-anomaly
  (testing "Adversarial — a command carrying :purpose + :scope plus a
            stray :unknown-key returns an ::anom/incorrect anomaly via
            cp/process-command (the same shape consumer code receives
            for any schema-rejected command). No event lands in the
            event store; no projection state mutates."
    (with-test-ctx [ctx]
      (let [ontology-id (random-uuid)
            bad-spec {:purpose "valid purpose"
                      :scope "valid scope"
                      :unknown-key "garbage"}
            result (record-spec! ctx ontology-id bad-spec)]
        (is (= ::anom/incorrect (::anom/category result))
            "Schema rejection surfaces as ::anom/incorrect — the
             standard Grain command-processor schema-failure category")
        (is (string? (::anom/message result))
            ":anom/message describes the failure")
        (is (nil? (:command-result/events result))
            "No events emitted on rejection")
        (Thread/sleep 50)
        (is (nil? (ontology/get-ontology-spec ctx ontology-id))
            "Projection state untouched")
        ;; Direct event-store read — belt + suspenders.
        (let [events (->> (es/read (:event-store ctx)
                                   {:tenant-id (:tenant-id ctx)
                                    :types #{:ontology/ontology-spec-recorded}})
                          (into []))]
          (is (zero? (count events))
              "Event store carries zero :ontology-spec-recorded events"))))))

;; =============================================================================
;; AC4 — All ORSD fields are optional
;; =============================================================================

(deftest sparse-spec-with-only-competency-questions-is-legal
  (testing "A spec carrying ONLY :competency-questions succeeds, emits
            an event with that body, and the projection returns it
            verbatim. Other fields stay absent — the projection never
            substitutes empty defaults."
    (with-test-ctx [ctx]
      (let [ontology-id (random-uuid)
            sparse {:competency-questions
                    ["Does the ontology have at least one labeled concept?"]}
            result (record-spec! ctx ontology-id sparse)]
        (is (not (some? (::anom/category result)))
            (str "Sparse spec must succeed — got "
                 (::anom/category result) " : "
                 (::anom/message result) " : "
                 (:error/explain result)))
        (Thread/sleep 100)
        (let [current (ontology/get-ontology-spec ctx ontology-id)]
          (is (= sparse current)
              "Projection returns the sparse body verbatim")
          (is (not (contains? current :purpose))
              "Absent fields stay absent — no defaulted-empty artefacts")
          (is (not (contains? current :scope))
              "Absent fields stay absent")
          (is (not (contains? current :non-functional))
              "Absent fields stay absent"))))))

;; =============================================================================
;; AC5 — Sparse-spec back-compat (no spec recorded for an ontology-id)
;; =============================================================================

(deftest no-spec-recorded-returns-nil-and-empty-history
  (testing "Regression — for an ontology-id with no
            :ontology/ontology-spec-recorded events,
            get-ontology-spec returns nil and
            get-ontology-spec-history returns []. Components that
            don't yet read the spec stay unaffected."
    (with-test-ctx [ctx]
      (let [unseen-ontology-id (random-uuid)]
        (is (nil? (ontology/get-ontology-spec ctx unseen-ontology-id))
            "Unseen ontology-id returns nil — no defaulted empty body")
        (is (= [] (ontology/get-ontology-spec-history ctx unseen-ontology-id))
            "Unseen ontology-id returns empty history vector")))))

;; =============================================================================
;; AC6 — Stored spec is readable later without re-passing
;; =============================================================================

(deftest stored-spec-is-readable-on-subsequent-projection
  (testing "Once a spec is recorded for an ontology-id, a fresh
            projection read (with no further commands dispatched)
            returns the same body. This is the contract that S15's
            CQ evaluator and S18's discovery-context assembler will
            rely on — they read the spec from the projection rather
            than re-passing it as a parameter."
    (with-test-ctx [ctx]
      (let [ontology-id (random-uuid)]
        (record-spec! ctx ontology-id sample-spec-v1)
        (Thread/sleep 100)
        ;; Simulate "a later code path reading the spec" — same ctx,
        ;; no intervening command, projection re-read.
        (let [later-read (ontology/get-ontology-spec ctx ontology-id)]
          (is (= sample-spec-v1 later-read)
              "Same body returned on the later projection read"))))))
