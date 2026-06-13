(ns ai.obney.orc.ontology.s18-rlm-discovery-test
  "S18 — recursive-RLM discovery wiring + ontology-discovery seed
   corpus tests.

   Verifies:
     - Seed corpus mechanics (loader, schema discipline, no truncation
       of :recommended-pattern, self-containment of :summary).
     - Discovery wiring construction (precondition guards on
       :ontology-id + :event-store, recursive-only).
     - Discovery output → S17 adapter (`compile-discovery-source!`)
       emits events through commands + the source stub is shaped for
       S17 ingest.
     - HITL status filtering (`require-hitl-reviewed-patterns?`).
     - Adversarial: malformed drafts surface clear anomalies; nothing
       silently drops.

   Discipline: tests go through the public interface
   (`ontology/run-discovery!`, `ontology/compile-discovery-source!`,
   `ontology/ontology-discovery-patterns`) — never internal helpers.
   Real Grain in-memory event store. No mocks of the event store, no
   try/catch swallowing exceptions."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [ai.obney.orc.ontology.interface.schemas]
            [ai.obney.orc.ontology.core.commands]
            [ai.obney.orc.ontology.core.read-models :as rm]
            [ai.obney.orc.ontology.interface :as ontology]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.query-processor.interface :as qp]
            [ai.obney.grain.pubsub.interface :as pubsub]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp]))

;; =============================================================================
;; Test context
;; =============================================================================

(defn- make-ctx []
  (rmp/l1-clear!)
  (let [ps (pubsub/start {:type :core-async :topic-fn :event/type})
        store (es/start {:conn {:type :in-memory} :event-pubsub ps :logger nil})
        dir (str "/tmp/s18-test-" (random-uuid))
        cache (kv/start (lmdb/->KV-Store-LMDB {:storage-dir dir :db-name "test"}))]
    {:event-store store
     :cache cache
     :tenant-id (random-uuid)
     :command-registry (cp/global-command-registry)
     :query-registry (qp/global-query-registry)
     :event-pubsub ps
     ::cache-dir dir}))

(defn- stop-ctx [ctx]
  (rmp/l1-clear!)
  (when-let [ps (:event-pubsub ctx)] (pubsub/stop ps))
  (when-let [c (:cache ctx)] (kv/stop c))
  (when-let [s (:event-store ctx)] (es/stop s))
  (when-let [d (::cache-dir ctx)]
    (let [f (java.io.File. d)]
      (when (.exists f)
        (doseq [c (.listFiles f)] (.delete c))
        (.delete f)))))

(defmacro with-ctx [[sym] & body]
  `(let [~sym (make-ctx)]
     (try ~@body (finally (stop-ctx ~sym)))))

;; =============================================================================
;; Seed corpus mechanics
;; =============================================================================

(deftest discovery-seed-corpus-loads-five-AFK-derived-patterns
  (testing "The ontology-discovery-patterns.edn ships exactly 5
            AFK-derived patterns, each with :hitl-status :auto-derived
            and required body fields."
    (let [patterns (ontology/ontology-discovery-patterns)]
      (is (= 5 (count patterns))
          "5 patterns derived from bench RESULTS")
      (doseq [p patterns]
        (is (some? (:target-id p))
            "Every seed has a :target-id")
        (is (some? (get-in p [:body :summary]))
            "Every seed has :body :summary")
        (is (some? (get-in p [:body :provenance]))
            "Every seed has :body :provenance documenting bench origin")
        (is (= :auto-derived (get-in p [:body :hitl-status]))
            "Every initial seed is :hitl-status :auto-derived")
        (is (true? (get-in p [:body :discovery-pattern?]))
            "Every seed flagged :discovery-pattern? true")
        (is (= :behavioral-subtree (get-in p [:body :scope]))
            "Every seed routes through :scope :behavioral-subtree so
             classify-behaviors retrieves it")
        (is (seq (get-in p [:body :strengths]))
            "Every seed has at least one :strengths entry")
        (is (seq (get-in p [:body :representative-uses]))
            "Every seed has :representative-uses examples")))))

(deftest discovery-seed-recommended-patterns-not-truncated
  (testing "Each :strengths :recommended-pattern is preserved
            verbatim — no truncation. Adversarially: assert the
            snippet contains substantive DSL keywords proving it's
            not a stub."
    (let [patterns (ontology/ontology-discovery-patterns)]
      (doseq [p patterns]
        (doseq [s (get-in p [:body :strengths])]
          (let [snippet (:recommended-pattern s)]
            (is (string? snippet) "Recommended-pattern is a string")
            (is (> (count snippet) 40)
                (str "Recommended-pattern non-trivial; got "
                     (count snippet) " chars"))
            (is (str/includes? snippet "[")
                "Recommended-pattern is a DSL form (starts with [")
            (is (or (str/includes? snippet ":llm")
                    (str/includes? snippet ":sequence")
                    (str/includes? snippet ":chunk-document")
                    (str/includes? snippet ":map-each")
                    (str/includes? snippet ":parallel")
                    (str/includes? snippet ":final"))
                "Recommended-pattern contains real DSL keywords")))))))

(deftest discovery-seed-summary-fields-are-self-contained
  (testing "Adversarial: a :summary leaking a file path or slice
            name would be a discipline violation. The model can't
            dereference those. Provenance is its own field."
    (let [patterns (ontology/ontology-discovery-patterns)]
      (doseq [p patterns]
        (let [summary (get-in p [:body :summary])]
          (is (string? summary))
          (is (> (count summary) 30) "Summary is substantive")
          ;; The summary must not contain file paths or slice
          ;; markers — those don't dereference for the model.
          (is (not (str/includes? summary "/seeds/"))
              "Summary doesn't reference filesystem paths")
          (is (not (re-find #"\b[Ss][0-9]+[a-z]?\b" summary))
              (str "Summary doesn't reference slice names; got: " summary))
          (is (not (re-find #"\b[a-f0-9]{7,}\b" summary))
              "Summary doesn't contain commit SHAs"))))))

(deftest discovery-seeds-emit-via-seed-baseline-corpus
  (testing "Calling seed-baseline-corpus! dispatches the 5 discovery
            patterns alongside the existing 68 baseline dispatches.
            Total grows from 68 to 73."
    (with-ctx [ctx]
      (let [results (ontology/seed-baseline-corpus! ctx)]
        (is (= 73 (count results))
            (str "Expected 68 baseline + 5 discovery = 73; got "
                 (count results)))))))

;; =============================================================================
;; Discovery wiring construction — precondition guards
;; =============================================================================

(deftest run-discovery-requires-ontology-id
  (testing "Discovery without :ontology-id is meaningless — the
            session refuses to construct (Disciplines #5)."
    (with-ctx [ctx]
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #"requires :ontology-id"
            (ontology/run-discovery!
              ctx
              {:sources [{:name :doc :type :text :content "some content"}]}))))))

(deftest run-discovery-requires-event-store
  (testing "Discovery requires :event-store on the ctx — without it
            the session can't grant S19 tools / S20 card. Refuse to
            construct."
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"requires :event-store"
          (ontology/run-discovery!
            {}
            {:ontology-id (random-uuid)
             :sources [{:name :doc :type :text :content "x"}]})))))

(deftest run-discovery-requires-sources
  (testing "Empty :sources is meaningless. Refuse."
    (with-ctx [ctx]
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #"requires :sources"
            (ontology/run-discovery!
              ctx
              {:ontology-id (random-uuid)
               :sources []}))))))

;; =============================================================================
;; HITL status filtering
;; =============================================================================

(deftest ontology-discovery-patterns-default-returns-all-seeds
  (testing "Default mode returns all patterns including auto-derived."
    (is (= 5 (count (ontology/ontology-discovery-patterns))))
    (is (= 5 (count (ontology/ontology-discovery-patterns false))))))

(deftest ontology-discovery-patterns-hitl-only-returns-empty-when-none-reviewed
  (testing "Adversarial: under :require-hitl-reviewed? true with
            zero reviewed seeds shipped, the filter returns empty —
            and that empty IS a legal return (the session proceeds
            with no patterns; it does not crash). Surfaces in the
            rlm-trace separately."
    (let [reviewed-only (ontology/ontology-discovery-patterns true)]
      (is (= 0 (count reviewed-only))
          "No shipped seeds are :hitl-reviewed yet — empty is the
           correct, transparent return"))))

;; =============================================================================
;; Discovery output → S17 adapter
;; =============================================================================
;; Build a synthetic discovery-output (the shape run-discovery! would
;; produce) and verify compile-discovery-source! emits events via
;; commands AND returns the source stub S17 can consume.

(def ^:private sample-discovery-output
  "A synthetic discovery output mirroring the shape `run-discovery!`
   produces on `:status :emitted-drafts`. Captured by hand rather than
   from a live LLM call so the adapter tests are reproducible. The
   live LLM verification lives in the s18 live-verify driver."
  {:status :emitted-drafts
   :emitted-concepts
   [{:uri "concept:legal/agreement"
     :label "Employment Agreement"
     :description "A signed contract between employer and employee."
     :scope :custom
     :evidence [{:source "doc-1"
                 :quote "This Employment Agreement is entered into..."}]}
    {:uri "concept:legal/employee"
     :label "Employee"
     :description "The individual party to an employment agreement."
     :scope :custom
     :evidence [{:source "doc-1" :quote "the Employee agrees to..."}]}]
   :emitted-relationships
   [{:source-uri "concept:legal/agreement"
     :target-uri "concept:legal/employee"
     :predicate "binds"
     :confidence-class :extracted
     :evidence [{:source "doc-1"
                 :quote "This Employment Agreement... binds the Employee..."}]}]
   :emitted-axioms []
   :rlm-trace [{:iteration 1
                :classified-pattern "DirectExtractionDiscovery"
                :tree-shape "single-llm"
                :outcome :emitted}]
   :iteration-reasonings ["Source is small; direct extraction is appropriate."]
   :patterns-offered 5})

(deftest compile-discovery-source-emits-concept-events
  (testing "The adapter emits concept events through
            :ontology/create-concept and returns a source stub the
            S17 build! can ingest as a parse-stage no-op."
    (with-ctx [ctx]
      (let [oid (random-uuid)
            stub (ontology/compile-discovery-source!
                   ctx oid sample-discovery-output)]
        (is (= :inline-concepts (:type stub))
            "Source stub uses S17's existing :inline-concepts type")
        (is (= [] (:concepts stub))
            "Stub carries no inline concepts — events already emitted")
        (is (= :ingested (get-in stub [:discovery-provenance :status])))
        (is (= 2 (get-in stub [:discovery-provenance :concepts-emitted])))
        (is (= 1 (get-in stub [:discovery-provenance :relationships-emitted])))
        (is (= 0 (get-in stub [:discovery-provenance :axioms-skipped])))
        ;; Verify the concept events actually landed.
        (let [concepts (filter #(= oid (:ontology-id %))
                               (rm/get-concepts ctx {}))]
          (is (= 2 (count concepts))
              "Two concept events landed in the event store"))))))

(deftest compile-discovery-source-emits-relationship-events
  (testing "The adapter emits relationship events through
            :ontology/create-relationship with evidence preserved."
    (with-ctx [ctx]
      (let [oid (random-uuid)
            _ (ontology/compile-discovery-source!
                ctx oid sample-discovery-output)
            relationships (filter #(= oid (:ontology-id %))
                                  (rm/get-relationships ctx))]
        (is (= 1 (count relationships))
            "Relationship event landed")
        (let [r (first relationships)]
          (is (= "binds" (:predicate r))))))))

(deftest compile-discovery-source-rejects-malformed-concept-draft
  (testing "Adversarial: a concept-draft missing :uri or :label
            raises ex-info — NO silent skip (Disciplines #5)."
    (with-ctx [ctx]
      (let [oid (random-uuid)
            bad {:status :emitted-drafts
                 :emitted-concepts [{:uri nil :label "missing uri"}]
                 :emitted-relationships []
                 :emitted-axioms []
                 :rlm-trace []
                 :patterns-offered 5}]
        (is (thrown-with-msg?
              clojure.lang.ExceptionInfo
              #"malformed concept-draft"
              (ontology/compile-discovery-source! ctx oid bad)))))))

(deftest compile-discovery-source-rejects-malformed-relationship-draft
  (testing "Adversarial: a relationship-draft missing required fields
            raises ex-info."
    (with-ctx [ctx]
      (let [oid (random-uuid)
            bad {:status :emitted-drafts
                 :emitted-concepts []
                 :emitted-relationships [{:source-uri "x"
                                          :target-uri nil
                                          :predicate "y"}]
                 :emitted-axioms []
                 :rlm-trace []
                 :patterns-offered 5}]
        (is (thrown-with-msg?
              clojure.lang.ExceptionInfo
              #"malformed relationship-draft"
              (ontology/compile-discovery-source! ctx oid bad)))))))

(deftest compile-discovery-source-rejects-non-emitted-status
  (testing "Adversarial: a discovery-output whose :status is
            :failed-at-session or :no-output cannot be compiled.
            The adapter raises explicitly so callers don't pass an
            empty / failed result through silently."
    (with-ctx [ctx]
      (let [oid (random-uuid)
            failed {:status :failed-at-session
                    :error "session crashed"}]
        (is (thrown-with-msg?
              clojure.lang.ExceptionInfo
              #":status :emitted-drafts"
              (ontology/compile-discovery-source! ctx oid failed)))))))

(deftest compile-discovery-source-preserves-axioms-as-skipped
  (testing "Axiom-drafts are preserved in the discovery-provenance
            but not emitted (S07 integration is a known gap, NOT a
            silent drop)."
    (with-ctx [ctx]
      (let [oid (random-uuid)
            with-axioms (assoc sample-discovery-output
                               :emitted-axioms
                               [{:axiom-type :closure
                                 :body {:set [:a :b]}
                                 :evidence []}])
            stub (ontology/compile-discovery-source! ctx oid with-axioms)]
        (is (= 1 (get-in stub [:discovery-provenance :axioms-skipped]))
            "Axiom-drafts counted under :axioms-skipped, not silently dropped")))))
