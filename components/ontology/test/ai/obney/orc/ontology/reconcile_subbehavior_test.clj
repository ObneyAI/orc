(ns ai.obney.orc.ontology.reconcile-subbehavior-test
  "EB5 — the RECONCILE subbehavior as a delegatable ORC sheet.

   These durable, HERMETIC tests lock the load-bearing STRUCTURE + CONTRACT + REUSE
   the EB5 live verify validated, through the subbehavior's PUBLIC surface (its
   registry name, its `:reads`/`:writes` contract + blackboard schema, its
   persisted node config) PLUS the two DETERMINISTIC EB5 deepenings exercised over a
   REAL Grain store WITHOUT ColBERT/LLM (the attribute-link logic + the
   reconcile-not-duplicate seam — both pure/structural, so they belong on the fast
   brick gate). They carry NO real ColBERT hybrid-search and NO LLM — that lives in
   the on-demand integration lane (`development/ontology-integration/.../eb5-
   reconcile-test`) + the live verify (`development/src/eb5_reconcile_subbehavior_
   live_verify.clj`, `docs/build-timeline/live-verify/EB5-reconcile.md`), per the
   gate-hygiene rule (a test driving real ColBERT is an INTEGRATION test).

   What is locked here:
     - Registry: name → deterministic sheet-id, idempotent re-registration (the
       EB1/EB2/EB3/EB4 pattern). The Reconcile sheet is SOURCE-AGNOSTIC.
     - Node design: a SINGLE `:code` node (reconcile is deterministic — the probe
       is P3 retrieval evidence, the merges are S12 evidence, the attribute links
       are structural; NO `:llm` node, NO `:rlm` config).
     - The public contract: [:ontology-id :concept-drafts :relationship-drafts
       :source-uri-sets] in, [:reconcile-report] out; the report write declares a
       STRUCTURED schema (defense-in-depth).
     - REUSE not fork: the `:code` node's `:fn` resolves to `reconcile-code`, which
       composes the reused `compile-discovery-source!` (land + V18) +
       `reconcile-graph!` (S03 + S12 + V18 entity-reconcile) + `hybrid-search`
       (P3 probe) + S12's `jaro-winkler-similarity` (the attribute-key match).
     - DEEPENING 2 (attribute granularity) — the genuinely-new EB5 logic — over a
       REAL store: a new entity's attribute links to an existing entity's
       attribute (structural key match + value equality), domain-agnostic.
     - The reconcile-not-duplicate seam over a REAL store: reconcile reads the
       CURRENT graph (not empty); a 2nd pass / a re-minted URI collapses, it does
       NOT duplicate."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.orc.orc-service.core.read-models :as orm]
            [ai.obney.orc.ontology.interface.schemas]
            [ai.obney.orc.ontology.core.commands]
            [ai.obney.orc.ontology.interface :as ontology]
            [ai.obney.orc.ontology.core.reconcile-subbehavior :as recon]
            [ai.obney.orc.ontology.core.discovery-tree :as dt]
            [ai.obney.orc.ontology.core.rlm-discovery :as rlm-discovery]
            [ai.obney.orc.ontology.core.dedup-cascade :as dedup]
            [ai.obney.orc.ontology.core.read-models :as rm]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.query-processor.interface :as qp]
            [ai.obney.grain.pubsub.interface :as pubsub]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp]
            [malli.core :as m]))

;; ---------------------------------------------------------------------------
;; Real-Grain harness for the deterministic-reconcile assertions (no ColBERT/LLM)
;; ---------------------------------------------------------------------------

(defn- make-ctx []
  (rmp/l1-clear!)
  (let [ps (pubsub/start {:type :core-async :topic-fn :event/type})
        store (es/start {:conn {:type :in-memory} :event-pubsub ps :logger nil})
        dir (str "/tmp/eb5-brick-" (random-uuid))
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

(defn- land! [ctx oid concepts relationships]
  (ontology/compile-discovery-source!
   ctx oid {:status :emitted-drafts
            :emitted-concepts concepts
            :emitted-relationships relationships}))

;; ---------------------------------------------------------------------------
;; Registry: name → deterministic sheet-id, idempotent, source-agnostic.
;; ---------------------------------------------------------------------------

(deftest registry-name-resolves-to-deterministic-idempotent-sheet-id-test
  (testing "the Reconcile subbehavior registers by name → a deterministic, idempotent sheet-id"
    (h/with-async-test-context [ctx]
      (let [id-1 (recon/register-reconcile-subbehavior! ctx {})
            looked-up (recon/reconcile-sheet-id-for)
            id-2 (recon/register-reconcile-subbehavior! ctx {})]
        (is (= id-1 looked-up)
            "name→sheet-id lookup must match the registered sheet-id")
        (is (= id-1 id-2)
            "re-registering an unchanged subbehavior is idempotent (same id)")
        (is (some? (orm/get-sheet-by-name ctx (recon/reconcile-subbehavior-name)))
            "the registered subbehavior is discoverable by name in the projection")))))

(deftest reconcile-subbehavior-is-source-agnostic-test
  (testing "ONE Reconcile sheet serves every source + graph (it reconciles the
            draft set it is handed against the :ontology-id it is handed; bakes in
            no source path) — like EB3 Model / EB4 Extract"
    (is (= "ontology-reconcile/reconcile@v1" (recon/reconcile-subbehavior-name))
        "the Reconcile registry name carries no source/medium/path tag")))

;; ---------------------------------------------------------------------------
;; Node design: a SINGLE :code node (deterministic — no :llm, no :rlm).
;; ---------------------------------------------------------------------------

(deftest body-is-single-deterministic-code-node-test
  (testing "the Reconcile body is a single :code node — reconcile is deterministic
            (P3-probe evidence + S12 merges + structural attribute links); NO :llm,
            NO :repl-researcher, NO :rlm config"
    (h/with-async-test-context [ctx]
      (let [sid (recon/register-reconcile-subbehavior! ctx {})
            nodes (vals (orm/get-nodes-by-id ctx sid))
            leaves (filter #(= :leaf (:type %)) nodes)
            code-leaves (filter #(= :code (:executor %)) leaves)
            llm-leaves (filter #(= :ai (:executor %)) leaves)]
        (is (= 1 (count code-leaves))
            "exactly one :code leaf (the reconcile orchestration)")
        (is (empty? llm-leaves)
            "no :ai (:llm) leaf — reconcile is deterministic")
        (is (empty? (filter #(= :repl-researcher (:type %)) nodes))
            "no :repl-researcher node")
        (is (nil? (some #(get % :rlm) nodes))
            "no :rlm config — Reconcile is a deterministic pipeline")))))

;; ---------------------------------------------------------------------------
;; The public contract + the :code node's :reads/:writes.
;; ---------------------------------------------------------------------------

(deftest reconcile-node-reads-drafts-and-scope-writes-report-test
  (testing "contract: the :code node reads [:ontology-id :concept-drafts
            :relationship-drafts :source-uri-sets] and writes [:reconcile-report]"
    (h/with-async-test-context [ctx]
      (let [sid (recon/register-reconcile-subbehavior! ctx {})
            node (first (filter #(= :code (:executor %)) (vals (orm/get-nodes-by-id ctx sid))))]
        (is (= [:ontology-id :concept-drafts :relationship-drafts :source-uri-sets]
               (vec (:reads node)))
            "reads the draft set + the current-graph scope")
        (is (= [:reconcile-report] (vec (:writes node)))
            "writes the single public reconcile-report")
        (is (= "ai.obney.orc.ontology.core.reconcile-subbehavior/reconcile-code"
               (:fn node))
            "the :code node's :fn is the reconcile-code wrapper")))))

(deftest report-write-schema-is-structured-not-bare-map-test
  (testing "the reconcile-report write declares a STRUCTURED schema (NOT a bare :map)"
    (is (= :map (first recon/reconcile-report-schema))
        "reconcile-report is a structured [:map …]")
    (is (> (count recon/reconcile-report-schema) 2)
        "a STRUCTURED [:map …] has field entries — a bare :map would not")
    (is (m/validate recon/reconcile-report-schema
                    {:ontology-id (random-uuid)
                     :mint-probe {:probed 2 :hits 1}
                     :entity-reconcile {:status :ok}
                     :attribute-reconcile {:links []}
                     :dangling-edge-count 0
                     :ambiguities-surfaced 0})
        "a real reconcile report validates the structured schema")
    (is (not (m/validate recon/reconcile-report-schema "a json string"))
        "a STRING does not validate the structured map schema")))

;; ---------------------------------------------------------------------------
;; REUSE not fork: the reconcile composes the proven machinery.
;; ---------------------------------------------------------------------------

(deftest reconcile-reuses-proven-machinery-not-fork-test
  (testing "the EB5 reconcile REUSES (does not fork) the proven fns — proven by
            their var being the real one (Discipline #8)"
    (is (var? #'ontology/compile-discovery-source!)
        "landing reuses interface/compile-discovery-source! (V18 always-on)")
    (is (var? #'dt/reconcile-graph!)
        "entity-reconcile reuses discovery-tree/reconcile-graph! (S03 + S12 + V18)")
    (is (var? #'ontology/hybrid-search)
        "check-before-mint reuses interface/hybrid-search (P3 BFS+embedding+ColBERT)")
    (is (var? #'dedup/jaro-winkler-similarity)
        "the attribute-key match reuses S12's jaro-winkler structural primitive"))
  (testing "the boundary is the orc-service INTERFACE (not core.dsl) — the
            poly-gate boundary-correctness rule"
    ;; the reconcile namespace requires the orc-service INTERFACE alias for the DSL
    ;; (build-workflow! / sheet-id-for-name / workflow / blackboard / sequence /
    ;; code / delegate) — never core.dsl directly. Proven by the registered sheet
    ;; existing (it could not build via a non-interface path on the brick).
    (h/with-async-test-context [ctx]
      (is (some? (recon/register-reconcile-subbehavior! ctx {}))
          "the sheet builds via the orc-service interface DSL boundary"))))

;; ---------------------------------------------------------------------------
;; DEEPENING 2 — attribute/feature granularity (the genuinely-new EB5 logic).
;; Pure structural unit, domain-agnostic.
;; ---------------------------------------------------------------------------

(deftest attribute-links-connects-new-attrs-to-existing-attrs-test
  (testing "a NEW entity's attribute links to an EXISTING entity's attribute by
            structural key match + value equality (same-value) or shared key
            (shared-key) — beyond reconcile-graph!'s entity level"
    (let [concepts [{:uri "existing:1" :attributes {:region "north" :weight 42}}
                    {:uri "existing:2" :attributes {:region "south"}}
                    {:uri "new:1" :attributes {:region "north" :tier "gold"}}]
          r (recon/attribute-links concepts #{"new:1"})
          links (:links r)
          same-value (filter #(= :same-value (:kind %)) links)
          shared-key (filter #(= :shared-key (:kind %)) links)]
      (is (pos? (count same-value))
          "a SAME-VALUE attribute link is found (new:1 :region north ↔ existing:1 :region north)")
      (is (some #(and (= "new:1" (:new-uri %)) (= "existing:1" (:existing-uri %))
                      (= :region (:new-attr-key %)) (= "north" (:value %)))
                same-value)
          "the same-value link names the precise new/existing attribute pair")
      (is (some #(and (= "new:1" (:new-uri %)) (= "existing:2" (:existing-uri %))
                      (= :region (:new-attr-key %)))
                shared-key)
          "a SHARED-KEY link (region differs in value) is also surfaced")
      (is (every? #(not= (:new-uri %) (:existing-uri %)) links)
          "no entity is linked to itself"))))

(deftest attribute-links-uses-structural-similarity-not-phrase-list-test
  (testing "the attribute-KEY match reuses S12's jaro-winkler structural similarity
            (NO hardcoded phrase list, #7) — a near-key (net-cost ↔ netcost) links,
            an unrelated key does not"
    (let [concepts [{:uri "e:1" :attributes {:net-cost 100}}
                    {:uri "e:2" :attributes {:color "red"}}
                    {:uri "n:1" :attributes {:netcost 100}}]
          r (recon/attribute-links concepts #{"n:1"})
          links (:links r)]
      (is (some #(and (= "n:1" (:new-uri %)) (= "e:1" (:existing-uri %)))
                links)
          "a structurally-near key (netcost ↔ net-cost) links via jaro-winkler")
      (is (not-any? #(= "e:2" (:existing-uri %)) links)
          "an unrelated key (:color) does NOT link"))))

(deftest attribute-links-empty-when-no-new-attrs-test
  (testing "no new entities / no attributes → empty link report (honest no-op)"
    (is (= [] (:links (recon/attribute-links [] #{}))))
    (is (= 0 (:same-value-link-count
              (recon/attribute-links
               [{:uri "x" :attributes {:a 1}}] #{}))))))

;; ---------------------------------------------------------------------------
;; The reconcile-not-duplicate seam over a REAL store (the maintain seam) — the
;; deterministic part (no ColBERT). Asserts events LANDED by reading the
;; projection back (Discipline #7).
;; ---------------------------------------------------------------------------

(def src-a-concepts
  [{:uri "entity:alpha" :label "Alpha (A)" :description "alpha from A"
    :attributes {:region "north" :weight 42}}
   {:uri "entity:beta" :label "Beta" :description "beta" :attributes {:weight 7}}])

(def src-b-concepts
  [{:uri "entity:alpha" :label "Alpha (B)" :description "the same alpha, from B"
    :attributes {:region "north" :tier "gold"}}
   {:uri "entity:gamma" :label "Gamma" :description "gamma" :attributes {:region "north"}}])

(deftest reconcile-against-prepopulated-graph-merges-not-duplicates-test
  (testing "reconcile reads the CURRENT graph (not empty): a re-minted URI
            collapses to ONE node (reconcile-not-duplicate), the cross-source link
            is reported, 0 dangling — asserted by reading the projection back (#7)"
    (with-ctx [ctx]
      (let [oid (random-uuid)
            _ (land! ctx oid src-a-concepts
                     [{:source-uri "entity:alpha" :target-uri "entity:beta" :predicate "relates-to"}])
            before (count (rm/get-concepts ctx {:ontology-id oid}))
            report (recon/reconcile-drafts!
                    ctx {:ontology-id oid
                         :concept-drafts src-b-concepts
                         :relationship-drafts
                         [{:source-uri "entity:gamma" :target-uri "entity:alpha" :predicate "relates-to"}]
                         :source-uri-sets
                         [{:source :a :uris #{"entity:alpha" "entity:beta"}}
                          {:source :b :uris #{"entity:alpha" "entity:gamma"}}]
                         ;; HERMETIC: deterministic probe signals only (the full
                         ;; P3 embedding+ColBERT probe is the integration lane).
                         :probe-signals #{:graph :lexical}})
            after (count (rm/get-concepts ctx {:ontology-id oid}))
            alpha-nodes (count (filter #(= "entity:alpha" (:uri %))
                                       (rm/get-concepts ctx {:ontology-id oid})))]
        ;; reconcile-not-duplicate: re-mint of alpha collapsed; only gamma grew.
        (is (= (inc before) after)
            "re-mint of entity:alpha collapsed; only the new entity:gamma grew the graph (projection read-back, #7)")
        (is (= 1 alpha-nodes)
            "the pre-existing alpha and the new mint are ONE node (not duplicated)")
        ;; entity-level cross-source link (reused reconcile-graph!).
        (is (= ["entity:alpha"]
               (get-in report [:entity-reconcile :shared-uri-links :shared-uris]))
            "the re-minted URI links the new source to the pre-existing graph")
        ;; 0 dangling (V18 reused).
        (is (= 0 (:dangling-edge-count report))
            "0 dangling edges after reconcile (V18)")
        ;; attribute-level link (the EB5 deepening): gamma :region ↔ alpha :region.
        (let [attr-links (get-in report [:attribute-reconcile :links])]
          (is (some #(and (= "entity:gamma" (:new-uri %))
                          (= :region (:new-attr-key %))
                          (= :same-value (:kind %)))
                    attr-links)
              "the NEW entity:gamma's :region attribute links to an existing :region attribute (same-value)"))))))

(deftest check-before-mint-probe-fires-pre-mint-and-is-honest-test
  (testing "the check-before-mint probe (a) fires over the UNLANDED drafts BEFORE
            landing, (b) is grounded in the REAL pre-existing graph (a pre-existing
            URI → :exact-uri? true; a genuinely-new URI → :exact-uri? false), (c)
            does NOT echo a draft's own URI back as a match"
    (with-ctx [ctx]
      (let [oid (random-uuid)
            _ (land! ctx oid src-a-concepts [])
            ;; probe source-B drafts BEFORE landing them. HERMETIC: restrict to
            ;; the deterministic #{:graph :lexical} signals (no embedding model /
            ;; ColBERT bridge) — the brick gate stays fast; the REAL full-P3 probe
            ;; (embedding + ColBERT) is the on-demand integration lane + live verify.
            probe (recon/check-before-mint-probe
                   ctx {:ontology-id oid :concept-drafts src-b-concepts
                        :signals #{:graph :lexical}})
            by-uri (into {} (map (juxt :uri identity) (:entries probe)))]
        (is (= 2 (:probed probe)) "both incoming drafts were probed")
        (is (true? (:exact-uri? (get by-uri "entity:alpha")))
            "entity:alpha is ALREADY in the graph (pre-existing) → :exact-uri? true (reconcile-not-duplicate)")
        (is (false? (:exact-uri? (get by-uri "entity:gamma")))
            "entity:gamma is genuinely new → :exact-uri? false (a fresh mint)")
        (is (false? (:match? (get by-uri "entity:gamma")))
            "the probe does NOT echo entity:gamma's own URI back as a pre-existing match (no self-echo)")))))

(deftest reconcile-requires-ontology-id-test
  (testing "reconcile fails loudly without a granted scope (no silent empty-graph
            default — Discipline #5)"
    (with-ctx [ctx]
      (is (thrown? clojure.lang.ExceptionInfo
                   (recon/reconcile-drafts! ctx {:ontology-id nil :concept-drafts []})))
      (is (thrown? clojure.lang.ExceptionInfo
                   (recon/check-before-mint-probe ctx {:ontology-id nil :concept-drafts []}))))))
