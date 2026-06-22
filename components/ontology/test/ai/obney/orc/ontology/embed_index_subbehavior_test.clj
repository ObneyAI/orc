(ns ai.obney.orc.ontology.embed-index-subbehavior-test
  "EB7 — the EMBED+INDEX subbehavior as a delegatable ORC sheet.

   These durable, HERMETIC tests lock the load-bearing STRUCTURE + CONTRACT +
   REUSE + the DETERMINISTIC field-resolution logic the EB7 live verify validated,
   through the subbehavior's PUBLIC surface (its registry name, its `:reads`/
   `:writes` contract + blackboard schema, its persisted node config) PLUS the
   embed path over a REAL Grain store (real DJL MiniLM is local + non-blocking, so
   the embed-and-read-back assertion belongs on the fast brick gate). They carry
   NO real ColBERT hybrid-search — that drives the Python bridge and lives in the
   on-demand integration lane (`development/ontology-integration/.../eb7-embed-
   index-test`) + the live verify (`development/src/eb7_embed_index_subbehavior_
   live_verify.clj`, `docs/build-timeline/live-verify/EB7-embed-index.md`), per the
   gate-hygiene rule (a test driving real ColBERT is an INTEGRATION test).

   What is locked here:
     - Registry: name → deterministic sheet-id, idempotent re-registration (the
       EB1-EB6 pattern). The Embed+Index sheet is SOURCE-AGNOSTIC.
     - Node design: a SINGLE `:code` node (deterministic — EB3 already committed
       `:embed-fields`; resolve/embed/index/read-back is orchestration, NO `:llm`
       node, NO `:rlm` config).
     - The public contract: [:ontology-id :embed-fields] in, [:embed-index-report]
       out; the report write declares a STRUCTURED schema (defense-in-depth).
     - Field resolution (the EB3 `:embed-fields` signal is PRIMARY; the heuristic
       schema scan is the FALLBACK; the canonical pair is the floor) — pure,
       domain-agnostic (#7/#12).
     - The GUARANTEED-by-default embed: with NO caller wiring, the `:code` node
       embeds the in-scope concepts on the resolved fields and the
       `:ontology/concept-embedded` events LAND — asserted by reading the
       projection back (discipline #7), over a REAL store.
     - Honest empty (#4): a graph with no embeddable text produces ZERO embeddings
       (no fabricated vectors) and an honest index-skip reason."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.orc.orc-service.core.read-models :as orm]
            [ai.obney.orc.ontology.interface.schemas]
            [ai.obney.orc.ontology.core.commands]
            [ai.obney.orc.ontology.interface :as ontology]
            [ai.obney.orc.ontology.core.embed-index-subbehavior :as ei]
            [ai.obney.orc.ontology.core.embedding :as embedding]
            [ai.obney.orc.ontology.core.read-models :as rm]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.query-processor.interface :as qp]
            [ai.obney.grain.pubsub.interface :as pubsub]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp]
            [clojure.set :as set]
            [malli.core :as m]))

;; ---------------------------------------------------------------------------
;; Real-Grain harness for the embed-and-read-back assertion (no ColBERT bridge).
;; ---------------------------------------------------------------------------

(defn- make-ctx []
  (rmp/l1-clear!)
  (let [ps (pubsub/start {:type :core-async :topic-fn :event/type})
        store (es/start {:conn {:type :in-memory} :event-pubsub ps :logger nil})
        dir (str "/tmp/eb7-brick-" (random-uuid))
        cache (kv/start (lmdb/->KV-Store-LMDB {:storage-dir dir :db-name "test"}))]
    {:event-store store :cache cache :tenant-id (random-uuid)
     :command-registry (cp/global-command-registry)
     :query-registry (qp/global-query-registry)
     :event-pubsub ps ::cache-dir dir}))

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

(defn- land! [ctx oid concepts]
  (ontology/compile-discovery-source!
   ctx oid {:status :emitted-drafts
            :emitted-concepts concepts
            :emitted-relationships []}))

(def embeddable-concepts
  [{:uri "entity:nurse" :label "Registered Nurse"
    :description "Provides direct patient care in hospitals and clinics."}
   {:uri "entity:engineer" :label "Software Engineer"
    :description "Designs builds and maintains large scale software systems."}
   {:uri "entity:teacher" :label "Elementary School Teacher"
    :description "Educates young children in core academic subjects."}])

(def non-embeddable-concepts
  [{:uri "entity:a" :label "" :description ""}
   {:uri "entity:b" :label "" :description ""}])

;; ---------------------------------------------------------------------------
;; Registry: name → deterministic sheet-id, idempotent, source-agnostic.
;; ---------------------------------------------------------------------------

(deftest registry-name-resolves-to-deterministic-idempotent-sheet-id-test
  (testing "the Embed+Index subbehavior registers by name → a deterministic, idempotent sheet-id"
    (h/with-async-test-context [ctx]
      (let [id-1 (ei/register-embed-index-subbehavior! ctx {})
            looked-up (ei/embed-index-sheet-id-for)
            id-2 (ei/register-embed-index-subbehavior! ctx {})]
        (is (= id-1 looked-up)
            "name→sheet-id lookup must match the registered sheet-id")
        (is (= id-1 id-2)
            "re-registering an unchanged subbehavior is idempotent (same id)")
        (is (some? (orm/get-sheet-by-name ctx (ei/embed-index-subbehavior-name)))
            "the registered subbehavior is discoverable by name in the projection")))))

(deftest embed-index-subbehavior-is-source-agnostic-test
  (testing "ONE Embed+Index sheet serves every source + graph (it embeds + indexes
            the :ontology-id it is handed; bakes in no source path) — like EB3-EB6"
    (is (= "ontology-embed-index/embed-index@v1" (ei/embed-index-subbehavior-name))
        "the Embed+Index registry name carries no source/medium/path tag")))

;; ---------------------------------------------------------------------------
;; Node design: a SINGLE :code node (deterministic — no :llm, no :rlm).
;; ---------------------------------------------------------------------------

(deftest body-is-single-deterministic-code-node-test
  (testing "the Embed+Index body is a single :code node — EB3 already committed
            :embed-fields, so resolve/embed/index is deterministic orchestration;
            NO :llm, NO :repl-researcher, NO :rlm config"
    (h/with-async-test-context [ctx]
      (let [sid (ei/register-embed-index-subbehavior! ctx {})
            nodes (vals (orm/get-nodes-by-id ctx sid))
            leaves (filter #(= :leaf (:type %)) nodes)
            code-leaves (filter #(= :code (:executor %)) leaves)
            llm-leaves (filter #(= :ai (:executor %)) leaves)]
        (is (= 1 (count code-leaves))
            "exactly one :code leaf (the embed+index orchestration)")
        (is (empty? llm-leaves)
            "no :ai (:llm) leaf — embed+index is deterministic")
        (is (empty? (filter #(= :repl-researcher (:type %)) nodes))
            "no :repl-researcher node")
        (is (nil? (some #(get % :rlm) nodes))
            "no :rlm config — Embed+Index is a deterministic pipeline")))))

;; ---------------------------------------------------------------------------
;; The public contract + the :code node's :reads/:writes.
;; ---------------------------------------------------------------------------

(deftest node-reads-scope-and-embed-fields-writes-report-test
  (testing "contract: the :code node reads [:ontology-id :embed-fields] and writes
            [:embed-index-report]"
    (h/with-async-test-context [ctx]
      (let [sid (ei/register-embed-index-subbehavior! ctx {})
            node (first (filter #(= :code (:executor %)) (vals (orm/get-nodes-by-id ctx sid))))]
        (is (= [:ontology-id :embed-fields] (vec (:reads node)))
            "reads the granted scope + EB3's embed-fields signal")
        (is (= [:embed-index-report] (vec (:writes node)))
            "writes the single public embed-index-report")
        (is (= "ai.obney.orc.ontology.core.embed-index-subbehavior/embed-index-code"
               (:fn node))
            "the :code node's :fn is the embed-index-code wrapper")))))

(deftest report-write-schema-is-structured-not-bare-map-test
  (testing "the embed-index-report write declares a STRUCTURED schema (NOT a bare :map)"
    (is (= :map (first ei/embed-index-report-schema))
        "embed-index-report is a structured [:map …]")
    (is (> (count ei/embed-index-report-schema) 2)
        "a STRUCTURED [:map …] has field entries — a bare :map would not")
    (is (m/validate ei/embed-index-report-schema
                    {:ontology-id (random-uuid)
                     :embed-fields-used [:description :label]
                     :embed-fields-source :model-signal
                     :embedded-count 3
                     :embeddings-read-back-count 3
                     :concepts-considered 3
                     :index-id (random-uuid)
                     :index-document-count 3})
        "a real embed-index report validates the structured schema")
    (is (not (m/validate ei/embed-index-report-schema "a json string"))
        "a STRING does not validate the structured map schema")))

;; ---------------------------------------------------------------------------
;; REUSE not fork: the embed+index composes the proven machinery.
;; ---------------------------------------------------------------------------

(deftest embed-index-reuses-proven-machinery-not-fork-test
  (testing "the EB7 embed+index REUSES (does not fork) the proven fns — proven by
            their var being the real one (Discipline #8)"
    (is (var? #'embedding/embed-concepts-batch!)
        "embedding reuses embedding/embed-concepts-batch! (the production batch-embed)")
    (is (var? #'ontology/detect-embeddable-fields-heuristic)
        "the fallback field detection reuses the heuristic detector")
    (is (var? #'ontology/get-colbert-index-for-ontology)
        "the index read-back reuses the per-ontology colbert-index resolver"))
  (testing "the boundary is the orc-service INTERFACE (not core.dsl) — the
            poly-gate boundary-correctness rule"
    (h/with-async-test-context [ctx]
      (is (some? (ei/register-embed-index-subbehavior! ctx {}))
          "the sheet builds via the orc-service interface DSL boundary"))))

;; ---------------------------------------------------------------------------
;; Field resolution — the EB3 signal is primary; the heuristic scan is fallback;
;; the canonical pair is the floor. Pure, domain-agnostic (#7/#12).
;; ---------------------------------------------------------------------------

(deftest resolve-embed-fields-prefers-the-model-signal-test
  (testing "EB3's :embed-fields signal is used VERBATIM (intersected with the
            command's embeddable enum), source :model-signal"
    (let [r (ei/resolve-embed-fields ["description"] embeddable-concepts)]
      (is (= #{:description} (:fields r)))
      (is (= :model-signal (:source r))))
    (testing "string OR keyword field names are tolerated"
      (is (= #{:label :description}
             (:fields (ei/resolve-embed-fields ["label" :description] embeddable-concepts)))))))

(deftest resolve-embed-fields-falls-back-to-heuristic-when-no-signal-test
  (testing "with NO signal, the heuristic schema scan over the concepts' own keys
            picks the semantic fields (:label/:description), source :heuristic"
    (let [r (ei/resolve-embed-fields nil embeddable-concepts)]
      (is (seq (:fields r)) "the heuristic detected at least one embeddable field")
      (is (set/subset? (:fields r) ei/embeddable-field-enum)
          "the detected fields are within the command's embeddable enum")
      (is (contains? #{:heuristic :canonical} (:source r))
          "the source is the heuristic scan (or the canonical floor)"))))

(deftest resolve-embed-fields-floors-to-canonical-pair-test
  (testing "an empty signal AND a degenerate (no-semantic) schema floor to the
            canonical #{:label :description} pair so a label/description-only
            concept is still embeddable (never empty fields)"
    (let [r (ei/resolve-embed-fields [] [{:uri "x"}])]
      (is (= #{:label :description} (:fields r)))
      (is (= :canonical (:source r))))
    (testing "a signal of ONLY non-command fields also floors to canonical"
      (is (= #{:label :description}
             (:fields (ei/resolve-embed-fields ["some-exotic-code-field"]
                                               embeddable-concepts)))))))

;; ---------------------------------------------------------------------------
;; GUARANTEED-by-default embed over a REAL store — no caller wiring; events LAND.
;; Asserted by reading the projection back (Discipline #7).
;;
;; GATE HYGIENE: the REAL-DJL embed-and-read-back (a heavyweight integration
;; dependency — the DJL/PyTorch native model is not on the fast `poly test`
;; classpath, only on `:dev:test`) is the on-demand INTEGRATION lane
;; (`development/ontology-integration/.../eb7-embed-index-test` + the live verify).
;; The brick gate stays HERMETIC: the HONEST-EMPTY path below exercises the
;; embed+index orchestration end-to-end over a REAL Grain store WITHOUT touching
;; DJL (blank text → no embed command → no model load), so it locks the no-false-
;; green contract (zero embeddings + honest index-skip) on the fast gate.
;; ---------------------------------------------------------------------------

(deftest embed-plus-index-honest-empty-no-fabricated-vectors-test
  (testing "a graph with NO embeddable text produces ZERO embeddings (no
            fabricated vectors) and an honest index-skip reason (#4) — exercises
            the embed+index orchestration over a REAL store without DJL"
    (with-ctx [ctx]
      (let [oid (random-uuid)
            _ (land! ctx oid non-embeddable-concepts)
            report (ei/embed+index! ctx {:ontology-id oid :embed-fields ["label" "description"]})
            embs (rm/get-all-concept-embeddings ctx {:ontology-id oid})]
        (is (empty? embs)
            "honest empty: no embeddings fabricated when there is no semantic content")
        (is (= 0 (:embedded-count report)))
        (is (= 0 (:embeddings-read-back-count report)))
        (is (= :no-document-content (:index-skipped-reason report))
            "no embeddable documents → honest index skip, no phantom index")
        (is (nil? (ontology/get-colbert-index-for-ontology ctx oid))
            "no ColBERT index registered when there is nothing to index")))))

(deftest embed-plus-index-requires-ontology-id-test
  (testing "embed+index! fails loudly without a granted scope (no silent
            empty-graph default — Discipline #5)"
    (with-ctx [ctx]
      (is (thrown? clojure.lang.ExceptionInfo
                   (ei/embed+index! ctx {:ontology-id nil :embed-fields []}))))))
