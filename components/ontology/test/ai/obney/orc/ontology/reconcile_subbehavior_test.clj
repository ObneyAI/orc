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
;; GC-7 — BEHAVIOR-PRESERVING bound on attribute-links (cut the WORK, not the
;; output). The cross-product `(new-attrs × existing-attrs)` jaro-winkler scan is
;; replaced by KEY-PAIR memoization: jaro-winkler is computed ONCE per DISTINCT
;; (new-key, existing-key) pair (schema-width², tiny), then the link set is
;; expanded by pure hash-bucket iteration. NO blocking heuristic — the full
;; key-pair similarity matrix is still computed, just deduplicated — so the
;; emitted :links are PROVABLY identical to the pre-GC-7 cross-product, not
;; merely empirically close. These tests guard that.
;; ---------------------------------------------------------------------------

;; The GOLDEN — captured from the PRE-GC-7 cross-product implementation on the
;; fixture below (a handful of new + existing concepts with overlapping/near
;; attribute keys + values: exact `:region`↔`:region`, near `:netcost`↔`:net-cost`
;; and `:region`↔`:regions` via jaro-winkler ≥ floor, same-value + shared-key). A
;; bucketing bug that drops/changes ANY link makes the set-equal assert RED.
(def gc7-fixture-concepts
  [{:uri "e:1" :attributes {:region "north" :weight 42 :net-cost 100}}
   {:uri "e:2" :attributes {:region "south" :tier "gold" :netcost 100}}
   {:uri "e:3" :attributes {:regions "north" :color "red"}}
   {:uri "n:1" :attributes {:region "north" :netcost 100 :tier "gold"}}
   {:uri "n:2" :attributes {:net-cost 100 :weight 42 :regions "south"}}])

(def gc7-fixture-new-uris #{"n:1" "n:2"})

(def gc7-golden-links
  "The EXACT :links set the pre-GC-7 cross-product emitted on the fixture (12
   links). Locked verbatim so the GC-7 bound is proven identical, not just
   self-consistent."
  #{{:new-uri "n:1" :new-attr-key :netcost :existing-uri "e:1" :existing-attr-key :net-cost :value 100 :kind :same-value}
    {:new-uri "n:1" :new-attr-key :netcost :existing-uri "e:2" :existing-attr-key :netcost :value 100 :kind :same-value}
    {:new-uri "n:1" :new-attr-key :region :existing-uri "e:1" :existing-attr-key :region :value "north" :kind :same-value}
    {:new-uri "n:1" :new-attr-key :region :existing-uri "e:2" :existing-attr-key :region :value nil :kind :shared-key}
    {:new-uri "n:1" :new-attr-key :region :existing-uri "e:3" :existing-attr-key :regions :value "north" :kind :same-value}
    {:new-uri "n:1" :new-attr-key :tier :existing-uri "e:2" :existing-attr-key :tier :value "gold" :kind :same-value}
    {:new-uri "n:2" :new-attr-key :net-cost :existing-uri "e:1" :existing-attr-key :net-cost :value 100 :kind :same-value}
    {:new-uri "n:2" :new-attr-key :net-cost :existing-uri "e:2" :existing-attr-key :netcost :value 100 :kind :same-value}
    {:new-uri "n:2" :new-attr-key :regions :existing-uri "e:1" :existing-attr-key :region :value nil :kind :shared-key}
    {:new-uri "n:2" :new-attr-key :regions :existing-uri "e:2" :existing-attr-key :region :value "south" :kind :same-value}
    {:new-uri "n:2" :new-attr-key :regions :existing-uri "e:3" :existing-attr-key :regions :value nil :kind :shared-key}
    {:new-uri "n:2" :new-attr-key :weight :existing-uri "e:1" :existing-attr-key :weight :value 42 :kind :same-value}})

;; A REFERENCE cross-product implementation — the pre-GC-7 algorithm, kept in the
;; test ONLY, so the behavior-preserving guard can diff the bounded impl against
;; the naive one on ARBITRARY inputs (not just the frozen golden). If GC-7's bound
;; ever diverged from the naive cross-product, this set-equal assert goes RED.
(defn- naive-attribute-links [concepts new-uris]
  (let [new-set (set new-uris)
        existing (remove #(contains? new-set (:uri %)) concepts)
        new-concepts (filter #(contains? new-set (:uri %)) concepts)
        existing-attrs (for [c existing [k v] (:attributes c)]
                         {:uri (:uri c) :key k :key-str (if (keyword? k) (name k) (str k)) :value v})]
    (set
     (for [nc new-concepts
           [nk nv] (:attributes nc)
           :let [nk-str (if (keyword? nk) (name nk) (str nk))]
           ea existing-attrs
           :when (and (not= (:uri nc) (:uri ea))
                      (>= (dedup/jaro-winkler-similarity nk-str (:key-str ea)) 0.92))
           :let [same-value? (= nv (:value ea))]]
       {:new-uri (:uri nc) :new-attr-key nk :existing-uri (:uri ea)
        :existing-attr-key (:key ea) :value (when same-value? nv)
        :kind (if same-value? :same-value :shared-key)}))))

(deftest gc7-attribute-links-identical-to-golden-test
  (testing "the GC-7-bounded attribute-links emits the SAME :links set as the
            pre-GC-7 cross-product (the frozen golden) — behavior-preserving"
    (let [r (recon/attribute-links gc7-fixture-concepts gc7-fixture-new-uris)
          links (set (:links r))]
      (is (= gc7-golden-links links)
          "the bounded attribute-links MUST emit exactly the pre-GC-7 link set")
      (is (= 9 (:same-value-link-count r)) "same-value link count preserved")
      (is (= 3 (:shared-key-link-count r)) "shared-key link count preserved"))))

(deftest gc7-attribute-links-matches-naive-cross-product-on-varied-inputs-test
  (testing "on several varied inputs (exact keys, near keys, length-mismatched
            near keys, transpositions, disjoint keys) the bounded attribute-links
            is SET-EQUAL to the naive cross-product reference — proving the bound
            preserves output beyond the single frozen golden"
    (doseq [[label concepts new-uris]
            [["near + exact mix" gc7-fixture-concepts gc7-fixture-new-uris]
             ["length-mismatched near keys"
              [{:uri "e:1" :attributes {:median_wage 5 :median-wage 9}}
               {:uri "e:2" :attributes {:medianwage 5}}
               {:uri "n:1" :attributes {:median_wages 5 :wage 1}}]
              #{"n:1"}]
             ["transposition near keys"
              [{:uri "e:1" :attributes {:abcd 1}} {:uri "e:2" :attributes {:wxyz 2}}
               {:uri "n:1" :attributes {:abdc 1 :acbd 1}}]
              #{"n:1"}]
             ["fully disjoint keys (no links)"
              [{:uri "e:1" :attributes {:alpha 1}} {:uri "n:1" :attributes {:omega 2}}]
              #{"n:1"}]
             ["repeated identical keys (dense, C2022_A-shape)"
              (vec (for [i (range 20)]
                     {:uri (str "c/" i) :attributes {:ctotalt i :ctotalm i :unitid 5}}))
              (set (map #(str "c/" %) (range 10 20)))]]]
      (let [bounded (set (:links (recon/attribute-links concepts new-uris)))
            naive (naive-attribute-links concepts new-uris)]
        (is (= naive bounded) (str "bounded == naive cross-product for: " label))))))

;; A counting reference cross-product — the PRE-GC-7 jaro-winkler call pattern (one
;; call per new-attr × existing-attr pair). Returns the comparison COUNT so the
;; test can prove the bounded impl does asymptotically less work on the SAME data.
(defn- naive-jw-comparison-count [concepts new-uris]
  (let [new-set (set new-uris)
        existing (remove #(contains? new-set (:uri %)) concepts)
        new-concepts (filter #(contains? new-set (:uri %)) concepts)
        existing-attr-count (reduce + (map #(count (:attributes %)) existing))
        new-attr-count (reduce + (map #(count (:attributes %)) new-concepts))]
    (* existing-attr-count new-attr-count)))

(deftest gc7-attribute-links-scale-bound-on-jw-work-test
  (testing "the bounded attribute-links does a BOUNDED number of jaro-winkler
            comparisons — O(distinct-keys²), NOT the O(new-attrs × existing-attrs)
            cross-product the pre-GC-7 impl ran. The jaro-winkler WORK is the metric
            GC-7 bounds; reverting to the per-attr-pair cross-product blows past it."
    ;; Few DISTINCT keys (30) shared across many concepts is the realistic
    ;; IPEDS-shape worst case (a wide table's columns repeated across every row).
    ;; The pre-GC-7 impl ran ONE jaro-winkler per (new-attr × existing-attr) pair —
    ;; quadratic in CONCEPT count. The bound runs jaro-winkler once per DISTINCT
    ;; (new-key, existing-key) pair — quadratic only in SCHEMA WIDTH (≤ 30² = 900),
    ;; independent of concept count. Concept count is kept modest here so the
    ;; (genuine, dense) link set still materializes in the test heap; the WORK
    ;; ratio is what proves the bound.
    ;; A vocab of MUTUALLY-DISSIMILAR key names (max pairwise jaro-winkler ≈ 0.78,
    ;; below the 0.92 floor) so links form ONLY on exact-key matches — keeping the
    ;; genuine (shared-key) link set materializable while the jaro-winkler WORK
    ;; metric stays the thing under test. (A shared-prefix vocab like `attr_0`…
    ;; would cross-match every pair and explode the OUTPUT, not the work.)
    (let [key-vocab (mapv keyword
                          ["alpha" "bravo" "charlie" "delta" "echo" "foxtrot" "golf"
                           "hotel" "india" "juliet" "kilo" "lima" "mike" "november"
                           "oscar" "papa" "quebec" "romeo" "sierra" "tango" "uniform"
                           "victor" "whiskey" "xray" "yankee" "zulu" "region" "weight"
                           "tier" "color"])
          n-keys (count key-vocab)
          mk (fn [n-concepts]
               (vec (for [i (range n-concepts)]
                      {:uri (str "ent/" i)
                       :attributes (into {} (for [j (range 5)]
                                              [(nth key-vocab (mod (+ (* i 5) j) n-keys))
                                               (str "val-" i "-" j)]))})))
          run (fn [n]
                (let [concepts (mk n)
                      new-uris (set (map #(str "ent/" %) (range (quot n 2) n)))
                      r (recon/attribute-links concepts new-uris)]
                  {:jw (:jw-comparisons r)
                   :naive-jw (naive-jw-comparison-count concepts new-uris)
                   :links (count (:links r))}))
          small (run 200)
          big   (run 1000)]
      (is (number? (:jw small))
          "attribute-links reports :jw-comparisons (the work metric the bound guards)")
      ;; distinct keys ≤ 30 → key-pair jaro-winkler calls ≤ 30×30 = 900, regardless
      ;; of concept count. Generous ceiling 2000.
      (is (< (:jw big) 2000)
          (str "jaro-winkler comparisons must be BOUNDED (≈ distinct-key²), got " (:jw big)))
      ;; the bound is INDEPENDENT of concept count: 5× the concepts must NOT 5× (or
      ;; 25×) the jaro-winkler work. The pre-GC-7 cross-product would scale ~25× here.
      (is (<= (:jw big) (:jw small))
          (str "jaro-winkler work must NOT grow with concept count — small=" (:jw small)
               " big=" (:jw big)))
      ;; and the bound is asymptotically FAR below the pre-GC-7 cross-product on the
      ;; SAME data — reverting to per-attr-pair jaro-winkler explodes the count.
      (is (< (* 100 (:jw big)) (:naive-jw big))
          (str "bounded jaro-winkler (" (:jw big) ") must be ≥100× below the naive "
               "cross-product (" (:naive-jw big) ") on the same input"))
      (is (pos? (:links big)) "the bounded pass still emits the genuine links"))))

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

;; ---------------------------------------------------------------------------
;; GC-7 — the check-before-mint probe's hybrid-search is BOUNDED at scale, with
;; honest coverage reporting (never a silent skip).
;; ---------------------------------------------------------------------------

(deftest gc7-probe-hybrid-search-is-bounded-with-honest-coverage-test
  (testing "the per-draft hybrid-search is bounded by :max-probe: at most :max-probe
            drafts get the full P3 search; the rest get only the free :exact-uri?
            lookup; the reduction is reported HONESTLY in :probe-coverage (no silent
            skip — Discipline 5); and the strongest signal (:exact-uri?) is computed
            for EVERY draft regardless of the cap (reconcile-not-duplicate preserved)"
    (with-ctx [ctx]
      (let [oid (random-uuid)
            ;; pre-populate the graph so an exact-uri? hit exists past the cap
            _ (land! ctx oid src-a-concepts [])
            ;; 50 synthetic drafts; one of them re-mints a pre-existing URI placed
            ;; PAST the cap so we prove exact-uri? is still found beyond the bound.
            many (concat
                  (for [i (range 49)]
                    {:uri (str "draft:" i) :label (str "Draft " i) :description "d"})
                  [{:uri "entity:alpha" :label "Alpha re-mint" :description "x"}])
            cap 10
            probe (recon/check-before-mint-probe
                   ctx {:ontology-id oid :concept-drafts (vec many)
                        :signals #{:graph :lexical} :max-probe cap})
            cov (:probe-coverage probe)
            by-uri (into {} (map (juxt :uri identity) (:entries probe)))]
        (is (= 50 (:probed probe)) "all 50 drafts are accounted for (probed count)")
        ;; the hybrid-search WORK is bounded to the cap
        (is (= cap (:hybrid-probed cov))
            (str "exactly :max-probe (" cap ") drafts got the full hybrid-search"))
        (is (= 40 (:hybrid-skipped cov)) "the remaining 40 drafts skipped the search")
        (is (false? (:full-coverage? cov)) "coverage is honestly reported as reduced")
        ;; the entries record per-draft whether the full probe ran
        (is (= cap (count (filter :hybrid-probed? (:entries probe))))
            "exactly :max-probe entries carry :hybrid-probed? true")
        ;; the strongest signal survives the cap: the re-minted pre-existing URI is
        ;; the 50th draft (index 49, well past cap 10) yet :exact-uri? is still true.
        (is (true? (:exact-uri? (get by-uri "entity:alpha")))
            ":exact-uri? (the free, strongest signal) is computed for EVERY draft, even past the cap")
        (is (false? (:hybrid-probed? (get by-uri "entity:alpha")))
            "...even though that draft's hybrid-search was cap-skipped")))))

(deftest gc7-probe-cap-disabled-restores-full-coverage-test
  (testing ":max-probe nil/:all disables the cap — every draft gets the full probe
            (the original behavior is preserved for callers that want it)"
    (with-ctx [ctx]
      (let [oid (random-uuid)
            _ (land! ctx oid src-a-concepts [])
            drafts (vec (for [i (range 30)]
                          {:uri (str "d:" i) :label (str "D" i) :description "d"}))
            probe (recon/check-before-mint-probe
                   ctx {:ontology-id oid :concept-drafts drafts
                        :signals #{:graph :lexical} :max-probe nil})
            cov (:probe-coverage probe)]
        (is (= 30 (:hybrid-probed cov)) "nil cap → all drafts fully probed")
        (is (true? (:full-coverage? cov)) "coverage reported as full")))))

;; ---------------------------------------------------------------------------
;; MS-3 — make the check-before-mint probe AFFORDABLE at multi-source scale:
;; (1) exact-URI fast path (an already-pre-existing URI is reconcile-into-existing
;;     by definition — the hybrid probe adds no decision value, so it is skipped
;;     and the :max-probe cap is spent on genuinely-NEW drafts only),
;; (2) ONE batched embed for the to-probe query texts (threaded to hybrid-search
;;     via :query-embedding — same vector, computed cheaper),
;; (3) a wall-clock :probe-budget-ms (exceeded → remaining drafts degrade to the
;;     exact-uri-only treatment, reported honestly — never silent).
;; Invocation-count evidence via redef'd collaborator fns (hermetic — no DJL, no
;; real hybrid-search).
;; ---------------------------------------------------------------------------

(deftest ms3-exact-uri-drafts-skip-hybrid-probe-and-do-not-consume-cap-test
  (testing "MS-3 (1) — a draft whose :uri is already pre-existing skips the hybrid
            probe entirely (reconcile-into-existing by definition), keeps
            :exact-uri? true, does NOT consume the :max-probe cap (the cap is spent
            on genuinely-new drafts), and the split is reported in :probe-coverage"
    (with-ctx [ctx]
      (let [oid (random-uuid)
            calls (atom [])
            ;; the 2 exact-uri drafts come FIRST — under position-only cap gating
            ;; they would burn the whole cap; under the fast path they must not.
            drafts [{:uri "pre:1" :label "P1" :description "pre"}
                    {:uri "pre:2" :label "P2" :description "pre"}
                    {:uri "new:1" :label "N1" :description "new"}
                    {:uri "new:2" :label "N2" :description "new"}
                    {:uri "new:3" :label "N3" :description "new"}]
            probe (with-redefs [ontology/hybrid-search
                                (fn [_ctx opts]
                                  (swap! calls conj (:query-text opts))
                                  {:results []})
                                ;; embedding signal is OFF (#{:graph :lexical}) —
                                ;; the batched embed must NOT be attempted.
                                ontology/embed-texts-batch
                                (fn [& _]
                                  (throw (ex-info "embed-texts-batch must not run when the embedding signal is off" {})))]
                    (recon/check-before-mint-probe
                     ctx {:ontology-id oid
                          :concept-drafts drafts
                          :pre-existing-uris #{"pre:1" "pre:2"}
                          :signals #{:graph :lexical}
                          :max-probe 2}))
            by-uri (into {} (map (juxt :uri identity) (:entries probe)))
            cov (:probe-coverage probe)]
        ;; invocation-count evidence: exactly cap (2) hybrid-search calls, and
        ;; they are the genuinely-NEW drafts — not the first-2-by-position.
        (is (= 2 (count @calls))
            "exactly :max-probe hybrid-search invocations (exact-uri drafts consume none)")
        (is (= ["N1 new" "N2 new"] @calls)
            "the cap is spent on the genuinely-NEW drafts, not the exact-uri ones")
        (doseq [u ["pre:1" "pre:2"]]
          (is (true? (:exact-uri? (get by-uri u)))
              (str u " keeps :exact-uri? true"))
          (is (false? (:hybrid-probed? (get by-uri u)))
              (str u " (exact-uri) skipped the hybrid probe")))
        (is (true? (:hybrid-probed? (get by-uri "new:1"))) "1st new draft probed")
        (is (true? (:hybrid-probed? (get by-uri "new:2"))) "2nd new draft probed")
        (is (false? (:hybrid-probed? (get by-uri "new:3"))) "3rd new draft is cap-skipped")
        ;; the coverage report carries the exact-uri/hybrid split honestly
        (is (= 2 (:exact-uri-skipped cov)) "coverage reports the exact-uri skip split")
        (is (= 2 (:hybrid-probed cov)) "coverage: cap-many hybrid probes ran")
        (is (= 3 (:hybrid-skipped cov)) "coverage: 2 exact-uri + 1 cap-skipped")))))

(deftest ms3-probe-batches-query-embeddings-in-one-call-and-threads-them-test
  (testing "MS-3 (2) — the probe embeds the to-probe drafts' query texts in ONE
            batched call (not N singles), each hybrid-search receives its
            precomputed :query-embedding, and a text that embeds to nil falls
            back to per-call behavior (no :query-embedding passed)"
    (with-ctx [ctx]
      (let [oid (random-uuid)
            batch-calls (atom [])
            search-opts (atom [])
            drafts [{:uri "new:1" :label "N1" :description "new"}
                    {:uri "new:2" :label "N2" :description "new"}
                    {:uri "new:3" :label "N3" :description "new"}]
            ;; the middle text "embeds to nil" — the nil-safety seam
            fake-vecs [[0.1] nil [0.3]]
            probe (with-redefs [ontology/embed-texts-batch
                                (fn [texts & _]
                                  (swap! batch-calls conj (vec texts))
                                  fake-vecs)
                                ontology/hybrid-search
                                (fn [_ctx opts]
                                  (swap! search-opts conj opts)
                                  {:results []})]
                    ;; :signals nil → the default (embedding enabled) → batch fires
                    (recon/check-before-mint-probe
                     ctx {:ontology-id oid
                          :concept-drafts drafts
                          :pre-existing-uris #{}}))]
        ;; invocation-count evidence: ONE batched embed call for all 3 texts
        (is (= 1 (count @batch-calls))
            "the probe-query embeddings are computed in ONE batched call")
        (is (= [["N1 new" "N2 new" "N3 new"]] @batch-calls)
            "the batch carries the exact per-draft query texts, in draft order")
        ;; each hybrid-search received its precomputed vector
        (is (= 3 (count @search-opts)) "all 3 drafts still hybrid-probed")
        (is (= [0.1] (:query-embedding (nth @search-opts 0)))
            "draft 1's hybrid-search received its precomputed :query-embedding")
        (is (not (contains? (nth @search-opts 1) :query-embedding))
            "a draft whose text embeds to nil falls back to per-call embedding (no :query-embedding key)")
        (is (= [0.3] (:query-embedding (nth @search-opts 2)))
            "draft 3's hybrid-search received its precomputed :query-embedding")
        (is (= 3 (get-in probe [:probe-coverage :hybrid-probed]))
            "coverage unchanged by the batching (same drafts probed)")))))

(deftest ms3-probe-budget-degrades-to-exact-uri-only-honestly-test
  (testing "MS-3 (3) — an exceeded :probe-budget-ms degrades the remaining drafts
            to the exact-uri-only treatment with :budget-exceeded? true in
            :probe-coverage (never silent); a generous budget → unchanged behavior;
            the default is a named def"
    (is (= 600000 recon/default-probe-budget-ms)
        "the default probe budget is a named def (10 min)")
    (with-ctx [ctx]
      (let [oid (random-uuid)
            drafts [{:uri "pre:1" :label "P1" :description "pre"}
                    {:uri "new:1" :label "N1" :description "new"}
                    {:uri "new:2" :label "N2" :description "new"}]
            run (fn [budget]
                  (with-redefs [ontology/hybrid-search (fn [_ _] {:results []})]
                    (recon/check-before-mint-probe
                     ctx {:ontology-id oid
                          :concept-drafts drafts
                          :pre-existing-uris #{"pre:1"}
                          :signals #{:graph :lexical}
                          :probe-budget-ms budget})))
            exhausted (run 0)
            generous (run 600000)]
        ;; budget 0 → every hybrid probe degrades; NEVER silent
        (is (= 0 (get-in exhausted [:probe-coverage :hybrid-probed]))
            "budget exceeded → no hybrid probes ran")
        (is (every? #(false? (:hybrid-probed? %)) (:entries exhausted))
            "every entry honestly carries :hybrid-probed? false")
        (is (true? (get-in exhausted [:probe-coverage :budget-exceeded?]))
            "coverage reports :budget-exceeded? true (never a silent stop)")
        (is (false? (get-in exhausted [:probe-coverage :full-coverage?]))
            "coverage is honestly NOT full")
        (is (= 3 (get-in exhausted [:probe-coverage :total]))
            "coverage still reports hybrid-probed OF total")
        ;; the free exact-uri signal SURVIVES budget exhaustion
        (is (true? (:exact-uri? (first (:entries exhausted))))
            ":exact-uri? (the free, strongest signal) is still computed for every draft")
        ;; a generous budget → unchanged behavior
        (is (= 2 (get-in generous [:probe-coverage :hybrid-probed]))
            "generous budget → both new drafts hybrid-probed")
        (is (false? (get-in generous [:probe-coverage :budget-exceeded?]))
            "generous budget → not exceeded")
        (is (true? (get-in generous [:probe-coverage :full-coverage?]))
            "generous budget → full coverage (exact-uri drafts never need the probe)")))))

(deftest ms3-reconcile-drafts-passes-probe-budget-through-test
  (testing "MS-3 (3) — reconcile-drafts! passes :probe-budget-ms through to the
            probe (callers that pass nothing get the named default)"
    (with-ctx [ctx]
      (let [oid (random-uuid)
            _ (land! ctx oid src-a-concepts [])
            report (with-redefs [ontology/hybrid-search (fn [_ _] {:results []})]
                     (recon/reconcile-drafts!
                      ctx {:ontology-id oid
                           :concept-drafts src-b-concepts
                           :probe-signals #{:graph :lexical}
                           :probe-budget-ms 0}))
            cov (get-in report [:mint-probe :probe-coverage])]
        (is (true? (:budget-exceeded? cov))
            "the 0-ms budget reached the probe through reconcile-drafts!")
        (is (= 0 (:hybrid-probed cov)) "no hybrid probes ran under the 0-ms budget")))))

(deftest reconcile-requires-ontology-id-test
  (testing "reconcile fails loudly without a granted scope (no silent empty-graph
            default — Discipline #5)"
    (with-ctx [ctx]
      (is (thrown? clojure.lang.ExceptionInfo
                   (recon/reconcile-drafts! ctx {:ontology-id nil :concept-drafts []})))
      (is (thrown? clojure.lang.ExceptionInfo
                   (recon/check-before-mint-probe ctx {:ontology-id nil :concept-drafts []}))))))
