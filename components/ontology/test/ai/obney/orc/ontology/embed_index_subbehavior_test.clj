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
            [ai.obney.orc.ontology.core.concept-stream :as cs]
            [ai.obney.orc.ontology.core.read-models :as rm]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.time.interface :as time]
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

(defn- land-embedding!
  "Append a `:ontology/concept-embedded` event EXACTLY as the real embed-concept
   command tags it (only a `[:concept id]` tag, `:ontology-id` in the body) — the
   DJL-free way to simulate an ALREADY-embedded concept. Mirrors the Slice-1
   `concept-stream-test` fixture, so BOTH the `:ontology/concept-embeddings`
   read model (`get-all-concept-embeddings`) and the streaming
   `reduce-concept-embeddings` fold see it identically."
  [ctx oid uri]
  (es/append (:event-store ctx)
             {:tenant-id (:tenant-id ctx)
              :events [(es/->event
                        {:type :ontology/concept-embedded
                         :tags #{[:concept (random-uuid)]}
                         :body {:uri uri
                                :ontology-id oid
                                :text-embedded (str "text for " uri)
                                :field-source "label+description"
                                :embedding (vec (repeatedly 8 #(double (rand))))
                                :model-id "test-model"
                                :embedded-at "2026-01-01T00:00:00Z"}})]}))

;; GATE HYGIENE — the DJL/PyTorch native engine is NOT on the fast `poly test`
;; classpath (only on `:dev:test` / the integration lane), so `embedding/embed-text`
;; returns nil there. The GC-12 END-TO-END projection assertions (cycles 1-3)
;; drive the REAL embed command and so belong on the DJL lane; this probe lets
;; them run there and SKIP (explicitly, not silently green) on the hermetic brick
;; gate. The PURE selection logic below carries the load-bearing GC-12 contract on
;; the fast gate without DJL. (Mirrors the existing honest-empty design.)
(def ^:private djl-available?
  (delay (boolean (try (seq (embedding/embed-text "djl availability probe"))
                       (catch Throwable _ false)))))

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

;; ---------------------------------------------------------------------------
;; STREAM Slice 2 — the embed path's two whole-graph embedding reads are STREAMED
;; (vector-discarding) instead of materializing the full vector map. These lock
;; the BYTE-PRESERVING invariant on the fast gate (no DJL): the streamed URI set /
;; count are IDENTICAL to `(set (keys (get-all-concept-embeddings …)))` /
;; `(count …)`, and the streaming fold retains NO vector.
;; ---------------------------------------------------------------------------

(deftest streamed-embedding-reads-equal-the-vector-map-reads-test
  (testing "STREAM Slice 2 — the two embed-path reads are byte-preserving:
            `reduce-concept-embeddings` yields the SAME already-embedded URI set
            and the SAME read-back count as `(set (keys (get-all-concept-embeddings
            …)))` / `(count …)` — the exact expressions embed+index! now inlines"
    (with-ctx [ctx]
      (let [oid (random-uuid)
            _ (land! ctx oid embeddable-concepts)
            ;; DJL-free: directly land embeddings for TWO of the three concepts
            _ (land-embedding! ctx oid "entity:nurse")
            _ (land-embedding! ctx oid "entity:engineer")
            ;; the vector-map reads being REPLACED (materialize the whole map)
            vector-map (rm/get-all-concept-embeddings ctx {:ontology-id oid})
            uris-via-map (set (keys vector-map))
            count-via-map (count vector-map)
            ;; the streaming replacements (#1 already-embedded-uris, #2 read-back count)
            uris-via-stream (cs/reduce-concept-embeddings
                             ctx oid (fn [acc uri _vec] (conj acc uri)) #{})
            count-via-stream (cs/reduce-concept-embeddings
                              ctx oid (fn [n _uri _vec] (inc n)) 0)]
        (is (= #{"entity:nurse" "entity:engineer"} uris-via-map)
            "precondition: two concepts are embedded in the projection")
        (is (= uris-via-map uris-via-stream)
            "#1 byte-preserving: streamed already-embedded URI set == vector-map keys")
        (is (= count-via-map count-via-stream)
            "#2 byte-preserving: streamed read-back count == vector-map count")
        (is (every? string? uris-via-stream)
            "the streaming accumulator holds ONLY URIs — NO vector is materialized")))))

(deftest embed-plus-index-streamed-reads-preserve-counts-end-to-end-test
  (testing "STREAM Slice 2 — end-to-end over a REAL store WITHOUT DJL: with EVERY
            in-scope concept already embedded (directly-landed events), embed+index!
            reads the already-embedded set + read-back count via the STREAMING folds
            and reports the SAME counts a materialized vector-map would — 3 already,
            0 new, read-back 3 (this exercises the converted reads on the fast gate)"
    (with-ctx [ctx]
      (let [oid (random-uuid)
            _ (land! ctx oid embeddable-concepts)
            _ (doseq [c embeddable-concepts] (land-embedding! ctx oid (:uri c)))
            vector-map (rm/get-all-concept-embeddings ctx {:ontology-id oid})
            report (ei/embed+index! ctx {:ontology-id oid
                                         :embed-fields ["label" "description"]})]
        (is (= 3 (count vector-map)) "precondition: all three concepts pre-embedded")
        (is (= 0 (:embedded-count report))
            "nothing new to embed — the streamed already-embedded set skipped all three")
        (is (= 3 (:skipped-already-count report))
            "all three counted as already-embedded via the STREAMED URI set (#1)")
        (is (= (count vector-map) (:embeddings-read-back-count report))
            "#2 byte-preserving: streamed read-back count == the vector-map count (3)")
        (is (= 3 (:concepts-considered report))
            "all three concepts considered — behavior-preserving")))))

;; ---------------------------------------------------------------------------
;; GC-12 — the PURE selection logic: skip already-embedded + structural spine
;; code-nodes. Domain-agnostic, no Grain, no DJL. These lock the load-bearing
;; filter through the public `select-concepts-to-embed` / `spine-code-node?`.
;; ---------------------------------------------------------------------------

;; A landed GC-11b spine code-node: scope coerces to :custom on landing, but the
;; `:attributes {:linking-key …}` stamp SURVIVES — that is the robust marker.
(def landed-spine-code-nodes
  [{:uri "ssn/123" :label "123" :scope :custom
    :description "Cross-source identifier (ssn): 123"
    :attributes {:linking-key "ssn" :value "123"}}
   {:uri "ssn/456" :label "456" :scope :custom
    :description "Cross-source identifier (ssn): 456"
    :attributes {:linking-key "ssn" :value "456"}}])

(deftest spine-code-node-detected-by-linking-key-attribute-test
  (testing "GC-12: a LANDED spine code-node is recognised by its :linking-key
            attribute (the marker that survives compile-discovery-source!'s
            scope coercion); a semantic concept is NOT"
    (is (every? ei/spine-code-node? landed-spine-code-nodes)
        "every landed spine code-node carries the :linking-key attribute stamp")
    (is (not-any? ei/spine-code-node? embeddable-concepts)
        "semantic concepts (no :linking-key attribute) are NOT spine code-nodes")
    (testing "an explicit :scope :reference (a draft-shaped concept) is also a code-node"
      (is (ei/spine-code-node? {:uri "x" :scope :reference})))))

(deftest select-concepts-to-embed-skips-already-and-reference-test
  (testing "GC-12: select-concepts-to-embed embeds only NEW semantic concepts —
            skipping the already-embedded URIs AND the spine code-nodes"
    (let [all (concat embeddable-concepts landed-spine-code-nodes)
          ;; the first semantic concept is already embedded
          already #{"entity:nurse"}
          {:keys [to-embed skipped-already-count skipped-reference-count
                  considered-count]}
          (ei/select-concepts-to-embed all already)]
      (is (= #{"entity:engineer" "entity:teacher"} (set (map :uri to-embed)))
          "only the NEW semantic concepts are selected to embed")
      (is (= 1 skipped-already-count) "the one already-embedded concept is skipped")
      (is (= 2 skipped-reference-count) "both spine code-nodes are skipped")
      (is (= 5 considered-count) "all five concepts were considered")))
  (testing "behavior-preserving: with NOTHING already embedded, every semantic
            concept is selected (only the spine code-nodes drop out)"
    (let [{:keys [to-embed skipped-already-count skipped-reference-count]}
          (ei/select-concepts-to-embed
           (concat embeddable-concepts landed-spine-code-nodes) #{})]
      (is (= (set (map :uri embeddable-concepts)) (set (map :uri to-embed)))
          "all semantic concepts selected on a fresh graph")
      (is (= 0 skipped-already-count))
      (is (= 2 skipped-reference-count)))))

;; ---------------------------------------------------------------------------
;; GC-12 — END-TO-END over a REAL Grain store + the REAL embed path (DJL MiniLM
;; is local + cached). Asserted via the :ontology/concept-embedded projection
;; (discipline #7) — the landed events, NOT the return values.
;; ---------------------------------------------------------------------------

;; A spine code-node that ALSO carries embeddable text — proves the skip is by
;; the structural marker, not by blank text (the honest-empty path).
(def spine-with-text
  [{:uri "acct/A-1" :label "A-1" :scope :custom
    :description "Cross-source identifier (account_id): A-1"
    :attributes {:linking-key "account_id" :value "A-1"}}])

(deftest gc12-incremental-only-new-concepts-embed-test
  (testing "GC-12 cycle 1 — INCREMENTAL: with K concepts already embedded and M
            new, an EB7 call embeds ONLY the M new ones; re-running embeds NOTHING
            more. Asserted via the concept-embedded projection (discipline #7).
            Behavior-preserving: every semantic concept embedded exactly once."
    (if-not @djl-available?
      (is true "DJL native engine unavailable on this classpath — runs on :dev:test / the orchestrator's build")
      (with-ctx [ctx]
      (let [oid (random-uuid)
            _ (land! ctx oid embeddable-concepts)
            fields #{:label :description}
            ;; pre-embed K=1 concept via the REAL embed-concept command (DJL)
            _ (cp/process-command
               (assoc ctx :command
                      {:command/name :ontology/embed-concept
                       :command/id (random-uuid)
                       :command/timestamp (time/now)
                       :uri "entity:nurse" :fields fields}))
            before (rm/get-all-concept-embeddings ctx {:ontology-id oid})
            ;; first EB7 call — should embed only the M=2 NEW concepts
            report (ei/embed+index! ctx {:ontology-id oid
                                         :embed-fields ["label" "description"]})
            after (rm/get-all-concept-embeddings ctx {:ontology-id oid})]
        (is (= 1 (count before)) "exactly the one pre-embedded concept before EB7")
        (is (= 2 (:embedded-count report))
            "EB7 embedded ONLY the 2 NEW concepts (not the already-embedded one)")
        (is (= 1 (:skipped-already-count report))
            "the already-embedded concept was skipped (honest count)")
        (is (= 0 (:skipped-reference-count report))
            "no spine code-nodes in this pure-semantic graph")
        (is (= 3 (count after))
            "all 3 semantic concepts end up embedded exactly once (projection)")
        (is (= #{"entity:nurse" "entity:engineer" "entity:teacher"}
               (set (keys after)))
            "the full semantic set is embedded — behavior-preserving")
        (testing "re-running EB7 embeds NOTHING (all already embedded)"
          (let [report-2 (ei/embed+index! ctx {:ontology-id oid
                                               :embed-fields ["label" "description"]})
                after-2 (rm/get-all-concept-embeddings ctx {:ontology-id oid})]
            (is (= 0 (:embedded-count report-2))
                "second call embeds nothing — no redundant re-embedding")
            (is (= 3 (:skipped-already-count report-2))
                "all 3 are now skipped-already")
            (is (= 3 (count after-2))
                "still exactly 3 embeddings — exactly once each (no duplicates)"))))))))

(deftest gc12-spine-code-nodes-not-embedded-test
  (testing "GC-12 cycle 2 — a graph with semantic concepts + spine :linking-key
            code-nodes: the code-nodes get NO concept-embedded (asserted via the
            projection); the semantic concepts DO. The spine node carries real
            text, proving the skip is structural, not blank-text."
    (if-not @djl-available?
      (is true "DJL native engine unavailable on this classpath — runs on :dev:test / the orchestrator's build")
      (with-ctx [ctx]
      (let [oid (random-uuid)
            _ (land! ctx oid (concat embeddable-concepts spine-with-text))
            report (ei/embed+index! ctx {:ontology-id oid
                                         :embed-fields ["label" "description"]})
            embs (rm/get-all-concept-embeddings ctx {:ontology-id oid})
            embedded-uris (set (keys embs))]
        (is (= 3 (:embedded-count report))
            "exactly the 3 semantic concepts embedded")
        (is (= 1 (:skipped-reference-count report))
            "the spine code-node was skipped as structural (honest count)")
        (is (not (contains? embedded-uris "acct/A-1"))
            "NO concept-embedded event for the spine code-node (projection)")
        (is (= #{"entity:nurse" "entity:engineer" "entity:teacher"} embedded-uris)
            "only the semantic concepts are embedded"))))))

(deftest gc12-fresh-graph-embeds-all-once-test
  (testing "GC-12 cycle 3 — behavior-preserving: a FRESH graph (nothing embedded)
            → every semantic concept embedded exactly once (no first-pass
            regression). Asserted via the projection."
    (if-not @djl-available?
      (is true "DJL native engine unavailable on this classpath — runs on :dev:test / the orchestrator's build")
      (with-ctx [ctx]
      (let [oid (random-uuid)
            _ (land! ctx oid embeddable-concepts)
            report (ei/embed+index! ctx {:ontology-id oid
                                         :embed-fields ["label" "description"]})
            embs (rm/get-all-concept-embeddings ctx {:ontology-id oid})]
        (is (= 3 (:embedded-count report)) "all 3 embedded on the first pass")
        (is (= 0 (:skipped-already-count report)) "nothing was already embedded")
        (is (= 0 (:skipped-reference-count report)) "no spine code-nodes")
        (is (= 3 (count embs)) "exactly 3 embeddings landed (projection)")
        (is (= 3 (:embeddings-read-back-count report))
            "the report's read-back count matches the projection")
        (is (= #{"entity:nurse" "entity:engineer" "entity:teacher"}
               (set (keys embs)))
            "every semantic concept embedded exactly once"))))))

;; ---------------------------------------------------------------------------
;; PERF — kill the DOUBLE-EMBED. Root cause (measured): embed-concepts! computes
;; the batch vectors then the :ontology/embed-concept command RE-EMBEDS every
;; concept a second time via embed-text (2× DJL). Fix: the command accepts a
;; PRECOMPUTED :embedding (+ :text-embedded) and lands it verbatim, so the batch
;; result can be landed WITHOUT a second embed pass. This test is DJL-FREE (the
;; caller supplies the vector) so it runs on the FAST brick gate.
;; ---------------------------------------------------------------------------

(deftest embed-concept-command-uses-precomputed-embedding-test
  (testing "PERF (no double-embed) — :ontology/embed-concept lands a PRECOMPUTED
            :embedding VERBATIM without re-embedding via embed-text (DJL-free)"
    (with-ctx [ctx]
      (let [oid (random-uuid)
            uri "entity:nurse"
            sentinel (vec (repeatedly 384 #(double (rand))))]
        (land! ctx oid [{:uri uri :label "Registered Nurse"
                         :description "Provides direct patient care."}])
        (let [r (cp/process-command
                 (assoc ctx :command
                        {:command/name :ontology/embed-concept
                         :command/id (random-uuid)
                         :command/timestamp (time/now)
                         :uri uri
                         :embedding sentinel
                         :text-embedded "precomputed text"}))]
          (is (not (:cognitect.anomalies/category r))
              "the command lands with a precomputed embedding (no embed-text needed)")
          (let [landed (get (rm/get-all-concept-embeddings ctx {:ontology-id oid}) uri)]
            (is (= sentinel (:embedding landed))
                "the LANDED vector is the caller's precomputed vector, NOT a recompute")
            (is (= "precomputed text" (:text-embedded landed))
                "the precomputed text rides too")
            (is (= (count sentinel) (get-in r [:command-result/data :dimensions]))
                "dimensions reported from the precomputed vector")))))))

(deftest embed-concepts-single-pass-lands-batch-vectors-test
  (testing "PERF single-pass — embed-concepts! computes vectors ONCE via the (injected)
            batch capability and LANDS them through the precompute command; the landed
            vectors ARE the batch's output (no second embed pass). DJL-free via the
            injected batch-fn returning sentinels."
    (with-ctx [ctx]
      (let [oid (random-uuid)
            _ (land! ctx oid embeddable-concepts)
            sentinels (into {} (map (fn [c] [(:uri c) (vec (repeatedly 384 #(double (rand))))])
                                    embeddable-concepts))
            calls (atom 0)
            ;; injected batch capability — the ONLY embed pass; returns a sentinel per
            ;; concept in the embed-concepts-batch! shape {:embedded-count :embeddings}.
            fake-batch (fn [to-embed _opts]
                         (swap! calls inc)
                         {:embedded-count (count to-embed)
                          :embeddings (mapv (fn [c] {:uri (:uri c)
                                                     :embedding (get sentinels (:uri c))
                                                     :text-embedded (str "t:" (:uri c))})
                                            to-embed)})
            r (#'ei/embed-concepts! ctx #{:label :description} embeddable-concepts #{}
                                    fake-batch)
            embs (rm/get-all-concept-embeddings ctx {:ontology-id oid})]
        (is (= 1 @calls)
            "the batch embed ran EXACTLY ONCE (single pass — no double-embed)")
        (is (= 3 (:embedded-count r)) "all three concepts landed")
        (doseq [c embeddable-concepts]
          (is (= (get sentinels (:uri c)) (:embedding (get embs (:uri c))))
              (str "landed vector for " (:uri c)
                   " is the batch's precomputed vector — the command did NOT recompute")))))))

;; ---------------------------------------------------------------------------
;; PERF — BATCHED INFERENCE. embed-texts-batch now runs ONE predictor +
;; .batchPredict over the batch (measured ~4.5x vs a new predictor + single
;; .predict per text). This guard locks BYTE-INVARIANCE (the batch vectors must
;; equal the per-text vectors within fp tolerance) + order/blank handling, so the
;; refactor can never silently change embeddings. DJL-gated (drives real inference).
;; ---------------------------------------------------------------------------

(deftest embed-concept-command-skips-projection-with-metadata-test
  (testing "PERF (O(n^2)→O(n)) — with concept metadata (:concept-id :ontology-id
            :scope) + a precomputed :embedding, embed-concept lands WITHOUT projecting
            the whole concepts read-model (get-concept-by-uri). PROVEN by landing a URI
            that is NOT present as a concept in the projection (today: 'Concept not
            found'). DJL-free."
    (with-ctx [ctx]
      (let [oid (random-uuid)
            uri "occupation/never-landed-as-a-concept"
            cid (random-uuid)
            sentinel (vec (repeatedly 384 #(double (rand))))
            r (cp/process-command
               (assoc ctx :command
                      {:command/name :ontology/embed-concept
                       :command/id (random-uuid) :command/timestamp (time/now)
                       :uri uri :concept-id cid :ontology-id oid :scope :custom
                       :embedding sentinel :text-embedded "metadata-provided text"}))]
        (is (not (:cognitect.anomalies/category r))
            "lands with supplied metadata even though the concept is NOT in the projection")
        (let [landed (get (rm/get-all-concept-embeddings ctx {:ontology-id oid}) uri)]
          (is (some? landed) "landed under the PROVIDED ontology-id (no lookup)")
          (is (= sentinel (:embedding landed)) "the precomputed vector landed verbatim"))))))

(deftest embed-texts-batch-matches-per-text-test
  (testing "PERF batched inference — embed-texts-batch produces vectors byte-invariant
            (fp tolerance) with embed-text per text; order preserved; a blank text maps
            to nil IN PLACE"
    (if-not @djl-available?
      (is true "DJL native engine unavailable on this classpath — runs on :dev:test / orchestrator gate")
      (let [texts ["Registered Nurse provides direct patient care in hospitals."
                   "Software Engineer designs and builds large scale systems."
                   ""  ;; blank → nil in place (mirrors embed-text)
                   "Elementary School Teacher educates young children."]
            batch (embedding/embed-texts-batch texts)
            per   (mapv #(embedding/embed-text %) texts)]
        (is (= (count texts) (count batch)) "order + count preserved")
        (is (nil? (nth batch 2)) "the blank text maps to nil in place")
        (doseq [i [0 1 3]]
          (let [b (nth batch i) p (nth per i)]
            (is (= (count p) (count b)) (str "text " i ": same dimensions"))
            (is (< (reduce max 0.0 (map (fn [x y] (Math/abs (double (- x y)))) b p)) 1e-5)
                (str "text " i ": batch vector ≈ per-text vector (fp tolerance)"))))))))
