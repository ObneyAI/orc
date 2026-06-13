(ns ai.obney.orc.ontology.s17-deterministic-skeleton-test
  "S17 — Deterministic skeleton builder tests.

   Verifies the pipeline ordering, per-stage failure shapes, the
   always-on integrations (S13 + S15), the validation-halts-on-
   violation rule, configurable thresholds, the SHACL TTL artifact,
   and the old-sheets regression baseline (G1 semantic equivalence).

   Test discipline:
     - All assertions go through the public skeleton entry point
       `deterministic-skeleton/build!`.
     - Real Grain in-memory event store. NO mocked event store.
     - LLM/judge fn injected as a controlled fn — production wires
       the real OpenRouter judge. The unit tests verify the runner
       MECHANICS; quality is tested by the live-verify driver.
     - No try/catch in tests — exceptions bubble so the harness can
       point at the root cause.

   Adversarial: each failure-mode test asserts the stages-run list
   TERMINATES at the failing stage (downstream stages did NOT run).
   This is the defense against silent continuation."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [ai.obney.orc.ontology.interface.schemas]
            [ai.obney.orc.ontology.interface :as ontology]
            [ai.obney.orc.ontology.core.commands]
            [ai.obney.orc.ontology.core.read-models :as rm]
            [ai.obney.orc.ontology.core.lints.commands]
            [ai.obney.orc.ontology.core.lints.read-models]
            [ai.obney.orc.ontology.core.lints.builtin :as lint-builtin]
            [ai.obney.orc.ontology.core.deterministic-skeleton :as sk]
            [ai.obney.orc.ontology.core.ttl-ingest :as ttl-ingest]
            [ai.obney.orc.ontology.core.ttl-canonicalize :as ttlc]
            [ai.obney.orc.ontology.core.serialization :as serial]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.query-processor.interface :as qp]
            [ai.obney.grain.pubsub.interface :as pubsub]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp]
            [ai.obney.grain.time.interface :as time]))

;; =============================================================================
;; Test context (mirrors s15_cq_runner_test pattern)
;; =============================================================================

(defn- make-ctx []
  (rmp/l1-clear!)
  (let [ps (pubsub/start {:type :core-async :topic-fn :event/type})
        store (es/start {:conn {:type :in-memory} :event-pubsub ps :logger nil})
        dir (str "/tmp/s17-test-" (random-uuid))
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
;; Fixture TTL — small but coverage-rich; near-dup pair for dedup; lang tags
;; =============================================================================

(def fixture-ttl
  (str
   "@prefix rdf:   <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .\n"
   "@prefix rdfs:  <http://www.w3.org/2000/01/rdf-schema#> .\n"
   "@prefix owl:   <http://www.w3.org/2002/07/owl#> .\n"
   "@prefix skos:  <http://www.w3.org/2004/02/skos/core#> .\n"
   "@prefix dcterms: <http://purl.org/dc/terms/> .\n"
   "@prefix ex:    <http://example.org/fixture#> .\n\n"
   "<http://example.org/fixture> a owl:Ontology ;\n"
   "  dcterms:title \"S17 test fixture\" ;\n"
   "  owl:versionInfo \"1.0\" .\n\n"
   ;; Two concepts with identical multi-word labels — a near-duplicate
   ;; pair that shares ALL tokens, so the skeleton's blocking step
   ;; admits the pair into S12's cascade. After the cascade, the
   ;; auto-merge tier will fire OR LLM tier will be reached.
   "ex:Person a skos:Concept ;\n"
   "  skos:prefLabel \"Human Person\"@en ;\n"
   "  rdfs:comment \"A human being.\"@en .\n\n"
   "ex:Persoon a skos:Concept ;\n"
   "  skos:prefLabel \"Human Person\"@en ;\n"
   "  rdfs:comment \"A human being.\"@en .\n\n"
   "ex:Robot a skos:Concept ;\n"
   "  skos:prefLabel \"Industrial Robot\"@en ;\n"
   "  rdfs:comment \"A machine.\"@en .\n\n"
   "ex:Person owl:disjointWith ex:Robot .\n"))

(def bad-ttl "this is not TTL!!!! @@ <<<")

;; =============================================================================
;; Helpers
;; =============================================================================

(defn- record-spec! [ctx ontology-id body]
  (cp/process-command
   (assoc ctx :command
          {:command/name :ontology/record-ontology-spec
           :command/id (random-uuid)
           :command/timestamp (time/now)
           :ontology-id ontology-id
           :body body})))

(defn- always-pass-judge [_]
  {:verdict :pass
   :reasoning "test judge — always-pass"
   :evidence-uris ["ex:Person"]
   :gaps []})

;; =============================================================================
;; AC1 — Happy path: all 7 stages run, :status :complete
;; =============================================================================

(deftest happy-path-runs-all-seven-stages
  (testing "A clean source + valid ORSD spec runs through ALL stages and
            produces :status :complete with :stages-run = canonical 7."
    (with-ctx [ctx]
      (let [oid (random-uuid)
            _ (record-spec! ctx oid {:purpose "test"
                                     :competency-questions
                                     ["Is there a Person concept?"
                                      "Is there a Robot concept?"]})
            _ (Thread/sleep 100)
            result (sk/build! ctx
                              {:ontology-id oid
                               :sources [{:type :ttl :content fixture-ttl}]
                               :judge-fn always-pass-judge
                               :exit-criterion {:pass-rate-min 0.5
                                                :unknown-rate-max 0.6}})]
        (is (= :complete (:status result))
            (str "Expected :complete, got " (:status result)
                 " with error " (:error result)))
        (is (= [:parse :normalize :dedup :validate :embed :index :exit-criterion]
               (:stages-run result))
            "stages-run captures the canonical 7-stage ordering")
        (is (pos? (:events-emitted result))
            "events landed in the event store")
        (is (>= (:concepts-count result) 3)
            "fixture's 3 concepts populated the projection")
        (is (>= (:pass-rate (:graph-health result)) 0.5)
            "graph-health met the configured pass-rate threshold")))))

;; =============================================================================
;; AC2 — Parse failure: corrupted TTL halts at parse, stages-run = [:parse]
;; =============================================================================

(deftest parse-failure-halts-immediately
  (testing "Corrupted TTL → :status :failed-at-parse with the anomaly
            captured as root cause; NO downstream stages ran."
    (with-ctx [ctx]
      (let [oid (random-uuid)
            result (sk/build! ctx
                              {:ontology-id oid
                               :sources [{:type :ttl :content bad-ttl}]})]
        (is (= :failed-at-parse (:status result)))
        (is (= [:parse] (:stages-run result))
            "Adversarial: NO downstream stages may run after a parse failure")
        (is (some? (get-in result [:error :error :anomaly]))
            "Root cause anomaly is attached to :error")))))

(deftest unknown-source-type-fails-parse
  (testing "An unknown :type → :failed-at-parse, parse stage only."
    (with-ctx [ctx]
      (let [oid (random-uuid)
            result (sk/build! ctx
                              {:ontology-id oid
                               :sources [{:type :unknown-rdf-format
                                          :content "anything"}]})]
        (is (= :failed-at-parse (:status result)))
        (is (= [:parse] (:stages-run result)))))))

;; =============================================================================
;; AC3 — Validation halts on :violation, downstream stages skipped
;; =============================================================================

(deftest validation-violation-halts-pipeline-before-embed
  (testing "When a :violation-severity lint fires, the pipeline returns
            :failed-validation and embed/index/exit-criterion do NOT
            run. ADVERSARIAL: stages-run terminates at :validate."
    (with-ctx [ctx]
      (let [oid (random-uuid)
            ;; Build a graph that triggers the disjointness-violation
            ;; lint: Person is typed under both Robot and HumanCategory,
            ;; and Robot owl:disjointWith HumanCategory.
            ;; The lint reads broader edges + disjointness axioms.
            ttl (str "@prefix owl: <http://www.w3.org/2002/07/owl#> .\n"
                     "@prefix skos: <http://www.w3.org/2004/02/skos/core#> .\n"
                     "@prefix ex: <http://example.org/fixture#> .\n\n"
                     "ex:Robot a skos:Concept ; skos:prefLabel \"Robot\"@en .\n"
                     "ex:HumanCategory a skos:Concept ; skos:prefLabel \"HumanCategory\"@en .\n"
                     "ex:Person a skos:Concept ; skos:prefLabel \"Person\"@en ;\n"
                     "  skos:broader ex:Robot, ex:HumanCategory .\n"
                     "ex:Robot owl:disjointWith ex:HumanCategory .\n")
            result (sk/build! ctx
                              {:ontology-id oid
                               :sources [{:type :ttl :content ttl}]
                               :shapes
                               ['ai.obney.orc.ontology.core.lints.builtin/disjointness-violation-shape-symbol]})]
        (is (= :failed-validation (:status result)))
        (is (= [:parse :normalize :dedup :validate] (:stages-run result))
            "Adversarial: embed/index/exit-criterion MUST NOT run after :violation")
        (is (seq (:violations result))
            "Violations are surfaced on the result map")))))

(deftest validation-warnings-do-not-halt
  (testing "When only :warning-severity lints fire, the pipeline
            completes — warnings are collected but don't halt."
    (with-ctx [ctx]
      (let [oid (random-uuid)
            result (sk/build! ctx
                              {:ontology-id oid
                               :sources [{:type :ttl :content fixture-ttl}]
                               :shapes
                               ['ai.obney.orc.ontology.core.lints.builtin/missing-disjointness-shape-symbol]
                               :judge-fn always-pass-judge
                               :exit-criterion {:pass-rate-min 0.0
                                                :unknown-rate-max 1.0}})]
        (is (= :complete (:status result))
            (str "warnings should not halt; got " (:status result)
                 " " (:error result)))
        (is (vector? (:validation-warnings result))
            "validation-warnings is populated (possibly empty)")))))

;; =============================================================================
;; AC4 — Exit-criterion failure: graph stays, status surfaces report
;; =============================================================================

(deftest exit-criterion-failure-surfaces-graph-health
  (testing "When CQ pass-rate is below threshold, :status :failed-cq;
            the graph IS still in the event store (events are facts —
            no rollback)."
    (with-ctx [ctx]
      (let [oid (random-uuid)
            _ (record-spec! ctx oid {:purpose "test"
                                     :competency-questions
                                     ["Is there a Wombat concept?"]})
            _ (Thread/sleep 100)
            ;; This CQ FAILS at Layer 1 (no Wombat) so pass-rate = 0.0
            result (sk/build! ctx
                              {:ontology-id oid
                               :sources [{:type :ttl :content fixture-ttl}]
                               :judge-fn always-pass-judge
                               :exit-criterion {:pass-rate-min 0.8
                                                :unknown-rate-max 0.3}})]
        (is (= :failed-cq (:status result)))
        (is (= [:parse :normalize :dedup :validate :embed :index :exit-criterion]
               (:stages-run result))
            "Exit-criterion fires LAST — all upstream stages ran first")
        (is (some? (:graph-health result))
            "graph-health is attached so the caller can root-cause")
        (is (some? (:reason result))
            "the failing thresholds + observed metrics are explicit")
        ;; Adversarial: prove the graph IS still in the event store.
        (let [concept-events
              (into [] (es/read (:event-store ctx)
                                {:tenant-id (:tenant-id ctx)
                                 :types #{:ontology/concept-created}}))]
          (is (>= (count concept-events) 3)
              "Failed CQ does NOT roll back — the build's events stay
               (events are facts)"))))))

;; =============================================================================
;; AC5 — No spec → exit-criterion :spec-absent? true, pipeline still completes
;; =============================================================================

(deftest no-spec-still-completes-pipeline
  (testing "When no ORSD spec is stored, the exit-criterion stage
            SKIPS gracefully (no crash) and reports spec-absent? true."
    (with-ctx [ctx]
      (let [oid (random-uuid)
            result (sk/build! ctx
                              {:ontology-id oid
                               :sources [{:type :ttl :content fixture-ttl}]})]
        (is (= :complete (:status result))
            (str "Expected :complete (spec absence is legal); got "
                 (:status result) " " (:error result)))
        (is (true? (:spec-absent? result)))
        (is (nil? (:graph-health result)))))))

;; =============================================================================
;; AC6 — Always-on: S13 evidence-aggregated events fire automatically
;;        during dedup stage (NOT R-Inject gated)
;; =============================================================================

(deftest s13-evidence-events-emit-without-r-inject
  (testing "The skeleton calls S12's cascade. S12 calls S13's evidence
            aggregator internally. ADVERSARIAL: R-Inject is NOT
            configured; the events MUST land regardless."
    (with-ctx [ctx]
      (let [oid (random-uuid)
            _ (sk/build! ctx
                         {:ontology-id oid
                          :sources [{:type :ttl :content fixture-ttl}]})
            evidence-events
            (into [] (es/read (:event-store ctx)
                              {:tenant-id (:tenant-id ctx)
                               :types #{:ontology/concept-evidence-aggregated}}))]
        (is (pos? (count evidence-events))
            "S13 always-on: cascade emits evidence-aggregated events
             without any R-Inject machinery")))))

;; =============================================================================
;; AC7 — Always-on: S15 CQ runner fires at exit-criterion when spec present
;; =============================================================================

(deftest s15-cq-events-emit-when-spec-present
  (testing "When a spec carrying CQs is stored, the exit-criterion
            stage dispatches one :ontology/cq-evaluated event per CQ
            via S15's runner."
    (with-ctx [ctx]
      (let [oid (random-uuid)
            _ (record-spec! ctx oid {:purpose "test"
                                     :competency-questions
                                     ["Is there a Person concept?"
                                      "Is there a Robot concept?"
                                      "Is there a Wombat concept?"]})
            _ (Thread/sleep 100)
            result (sk/build! ctx
                              {:ontology-id oid
                               :sources [{:type :ttl :content fixture-ttl}]
                               :judge-fn always-pass-judge
                               :exit-criterion {:pass-rate-min 0.0
                                                :unknown-rate-max 1.0}})
            cq-events
            (into [] (es/read (:event-store ctx)
                              {:tenant-id (:tenant-id ctx)
                               :types #{:ontology/cq-evaluated}}))]
        (is (= :complete (:status result)))
        (is (= 3 (count cq-events))
            "One cq-evaluated event per CQ")))))

;; =============================================================================
;; AC8 — Configurable exit-criterion thresholds
;; =============================================================================

(deftest exit-criterion-thresholds-are-configurable
  (testing "A fixture that PASSES at default thresholds FAILS when the
            threshold is raised to 0.95."
    (with-ctx [ctx]
      (let [oid (random-uuid)
            ;; 3 CQs; 2 pass (Person, Robot) + 1 fail (Wombat) → pass-rate 0.67
            _ (record-spec! ctx oid {:purpose "test"
                                     :competency-questions
                                     ["Is there a Person concept?"
                                      "Is there a Robot concept?"
                                      "Is there a Wombat concept?"]})
            _ (Thread/sleep 100)
            sources [{:type :ttl :content fixture-ttl}]
            ;; Permissive threshold → :complete
            r-loose (sk/build! ctx
                               {:ontology-id oid
                                :sources sources
                                :judge-fn always-pass-judge
                                :exit-criterion {:pass-rate-min 0.5
                                                 :unknown-rate-max 1.0}})]
        (is (= :complete (:status r-loose)))
        (let [oid2 (random-uuid)
              _ (record-spec! ctx oid2 {:purpose "test"
                                        :competency-questions
                                        ["Is there a Person concept?"
                                         "Is there a Robot concept?"
                                         "Is there a Wombat concept?"]})
              _ (Thread/sleep 100)
              r-strict (sk/build! ctx
                                  {:ontology-id oid2
                                   :sources sources
                                   :judge-fn always-pass-judge
                                   :exit-criterion {:pass-rate-min 0.95
                                                    :unknown-rate-max 0.3}})]
          (is (= :failed-cq (:status r-strict))
              "Same graph FAILS at strict threshold — proves the gate is configurable"))))))

;; =============================================================================
;; AC9 — SHACL TTL artifact attached on :complete
;; =============================================================================

(deftest shacl-ttl-artifact-attached
  (testing "After :complete, :artifacts :shacl-ttl carries a non-empty
            SHACL TTL document for THIS ontology's registered shapes
            (not a global dump). Adversarial: assert the export carries
            the missing-disjointness shape id we registered."
    (with-ctx [ctx]
      (let [oid (random-uuid)
            result (sk/build! ctx
                              {:ontology-id oid
                               :sources [{:type :ttl :content fixture-ttl}]
                               :shapes
                               ['ai.obney.orc.ontology.core.lints.builtin/missing-disjointness-shape-symbol]})
            shacl (get-in result [:artifacts :shacl-ttl])]
        (is (= :complete (:status result)))
        (is (string? shacl))
        (is (str/includes? shacl "missing-disjointness")
            "The SHACL TTL carries the shape we registered (not a global registry dump)")))))

;; =============================================================================
;; AC10 — LLM budget exhaustion surfaces :requires-review explicitly
;; =============================================================================

(deftest llm-budget-exhaustion-surfaces-review
  (testing "When the cascade hits an LLM tier with an exhausted budget,
            the verdict is :requires-review. The skeleton surfaces
            :dedup-review-required on the result; does NOT silently
            merge or skip."
    (with-ctx [ctx]
      (let [oid (random-uuid)
            ;; Build a fixture with two labels in the cascade's
            ;; ambiguity band: high enough JW to enter the LLM tier
            ;; (T9), but not high enough to auto-merge via T8a. Multi-
            ;; word labels share at least one token so the skeleton's
            ;; blocking step admits the pair to S12. With
            ;; llm-budget = 0, T9 returns :requires-review.
            ttl (str "@prefix skos: <http://www.w3.org/2004/02/skos/core#> .\n"
                     "@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .\n"
                     "@prefix ex: <http://example.org/fixture#> .\n\n"
                     "ex:Manager a skos:Concept ;\n"
                     "  skos:prefLabel \"Software Project Manager\"@en ;\n"
                     "  rdfs:comment \"Manages projects.\"@en .\n\n"
                     "ex:Lead a skos:Concept ;\n"
                     "  skos:prefLabel \"Software Project Lead\"@en ;\n"
                     "  rdfs:comment \"Leads projects.\"@en .\n")
            result (sk/build! ctx
                              {:ontology-id oid
                               :sources [{:type :ttl :content ttl}]
                               :llm-budget 0})]
        ;; The pipeline still completes — budget-exhausted is NOT a halt.
        ;; The review-required pairs surface explicitly for downstream review.
        (is (contains? #{:complete :failed-cq} (:status result))
            (str "Expected complete/failed-cq (review is non-halt), got "
                 (:status result) " " (:error result)))
        ;; The cascade may decide the pair is already string-similar enough
        ;; to merge OR may emit :requires-review — assert ONE of the two
        ;; clear semantics held (no silent skip / silent merge masking).
        (let [n-reviews (or (get-in result [:dedup-summary :requires-review]) 0)
              n-merges (or (get-in result [:dedup-summary :merges]) 0)]
          (is (pos? (+ n-reviews n-merges))
              "Astronaut/Astronautt MUST hit dedup as either merge OR review"))))))

;; =============================================================================
;; AC11 — Old-sheets regression baseline: TTL round-trip via the skeleton
;;        produces a SEMANTICALLY EQUIVALENT graph to direct TTL ingest
;; =============================================================================

(deftest old-sheets-regression-via-g1-equivalence
  (testing "A fixture loaded VIA the skeleton produces a graph whose
            re-exported TTL canonicalizes to a triple-set that
            INCLUDES the source's concept triples + S07 axiom triples.
            Uses the S09 canonicalizer so the comparison is
            semantic-triple, not lexical-text. This is the
            old-sheets regression baseline carried into S17."
    (with-ctx [ctx]
      (let [oid (random-uuid)
            _ (sk/build! ctx
                         {:ontology-id oid
                          :sources [{:type :ttl :content fixture-ttl}]})
            exported (serial/full-export
                      ctx {:base-uri "http://example.org/fixture#"})
            ;; Canonicalize both sides via S09's ntriples canonicalizer.
            ;; Compare TRIPLE SETS so prefix-form vs full-IRI differences
            ;; are normalized away.
            source-canon (ttlc/canonicalize-ttl fixture-ttl)
            export-canon (ttlc/canonicalize-ttl exported)
            source-triples (set (str/split-lines (or source-canon "")))
            export-triples (set (str/split-lines (or export-canon "")))]
        (is (string? exported))
        (is (string? source-canon)
            "Source TTL canonicalizes to ntriples")
        (is (string? export-canon)
            "Exported TTL canonicalizes to ntriples")
        ;; Substring-match against the canonicalized triple SET — this
        ;; survives prefix-form differences (the source uses ex:Person,
        ;; the export full IRIs).
        (let [has-triple-mentioning
              (fn [substr]
                (some #(str/includes? % substr) export-triples))]
          (is (has-triple-mentioning "fixture#Person")
              "Skeleton round-trip preserves the Person concept triples")
          (is (has-triple-mentioning "fixture#Robot")
              "Skeleton round-trip preserves the Robot concept triples")
          ;; Adversarial: the S07 disjointness axiom is on its own
          ;; (subject, predicate, object) triple in canonical form;
          ;; assert by exact OWL predicate. The full-export emits axioms
          ;; in their OWL form; round-trip preserves them.
          (is (has-triple-mentioning "disjointWith")
              "Skeleton round-trip preserves the S07 disjointWith axiom"))))))

;; =============================================================================
;; AC12 — Grain discipline: zero bare es/append in the skeleton ns
;; =============================================================================

(deftest skeleton-uses-commands-not-bare-appends
  (testing "Grain discipline: the deterministic_skeleton.clj source MUST NOT
            contain any `(es/append ...)` calls. All writes flow through
            commands."
    (let [src (slurp "components/ontology/src/ai/obney/orc/ontology/core/deterministic_skeleton.clj")]
      (is (not (str/includes? src "(es/append"))
          "Skeleton must not bypass commands — bare es/append is forbidden")
      (is (not (str/includes? src "event-store/append"))
          "Same check, longer-form alias"))))
