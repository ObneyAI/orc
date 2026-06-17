(ns ai.obney.orc.ontology.dt7-cross-source-reconcile-test
  "DT7 — cross-source linking / reconciliation against current graph state.

   Verifies the graph-level reconciliation pass (PRD M5) through its PUBLIC
   interface (`dt/reconcile-graph!` + the pure `dt/shared-uri-links`) over a REAL
   Grain in-memory event store — never prompt-internal or projection-internal
   assertions. The four load-bearing behaviors:

     1. LINK across sources — entities from different sources that refer to the
        same real thing merge. Shared canonical URI collapses to ONE node
        (the URI-keyed projection); near-variant identities surface (see #2).
     2. SURFACE ambiguities — a near-variant identity (a different encoding of
        the same id) is FLAGGED (reusing V18's ambiguity surfacing + the S12
        cascade's :requires-review band), never silently merged or dropped.
     3. 0 dangling — V18 referential integrity holds over the reconciled graph.
     4. THE LOAD-BEARING SEAM — reconcile reads + reconciles against the CURRENT
        GRAPH STATE (NOT an empty graph): running it twice / against a pre-
        populated graph RECONCILES (merges / no-ops), it does NOT duplicate.

   Reuse, not fork (Discipline #8): the pass re-orchestrates S03 (alignment
   register), S12 (LSH blocking + cascade), V18 (auto-mint implied + ambiguity).
   Domain-agnostic (Discipline #12): every fixture URI is neutral (entity:* /
   record:* / src-a:* / src-b:*) — NO CIP/SOC/education/industry code format.

   Discipline #4: the REAL cross-source LLM proof is the DT7-linking live verify;
   these tests exercise the DETERMINISTIC reconciliation orchestration (the
   linking is shared-id / structural-similarity / V18 — no LLM needed) over a
   real Grain store so the contract + the idempotency seam are pinned."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.obney.orc.ontology.interface.schemas]
            [ai.obney.orc.ontology.core.commands]
            [ai.obney.orc.ontology.core.read-models :as rm]
            [ai.obney.orc.ontology.core.discovery-tree :as dt]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.query-processor.interface :as qp]
            [ai.obney.grain.pubsub.interface :as pubsub]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp]
            [ai.obney.grain.time.interface :as time]))

;; =============================================================================
;; Harness (mirrors the v18 / dt5 suites)
;; =============================================================================

(defn- make-ctx []
  (rmp/l1-clear!)
  (let [ps (pubsub/start {:type :core-async :topic-fn :event/type})
        store (es/start {:conn {:type :in-memory} :event-pubsub ps :logger nil})
        dir (str "/tmp/dt7-test-" (random-uuid))
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

(defn- mint! [ctx oid {:keys [uri label description]}]
  (cp/process-command
   (assoc ctx :command {:command/name :ontology/create-concept
                        :command/id (random-uuid)
                        :command/timestamp (time/now)
                        :ontology-id oid :uri uri :label label
                        :description description :scope :custom
                        :broader [] :indicators []})))

(defn- rel! [ctx oid {:keys [s p t]}]
  (cp/process-command
   (assoc ctx :command {:command/name :ontology/create-relationship
                        :command/id (random-uuid)
                        :command/timestamp (time/now)
                        :ontology-id oid :source-uri s :target-uri t
                        :predicate p :confidence-class :extracted :properties {}})))

(defn- concept-uris [ctx oid]
  (set (map :uri (rm/get-concepts ctx {:ontology-id oid}))))

(defn- concept-count [ctx oid]
  (count (rm/get-concepts ctx {:ontology-id oid})))

;; A two-source extraction landed in the graph. Domain-neutral URIs:
;;   src-a (a structured source) minted: code:100, occ:200
;;   src-b (another structured source) minted: code:100 (the SAME canonical URI
;;          — must collapse), inst:300, and references occ:200 via the variant
;;          encoding occ:200_x by an edge whose endpoint is not minted (so V18
;;          must auto-mint it + flag the near-variant ambiguity).
(defn- seed-two-sources! [ctx oid]
  (mint! ctx oid {:uri "code:100" :label "Program One (source A)"
                  :description "A program, as source A describes it"})
  (mint! ctx oid {:uri "occ:200" :label "Occupation Two"
                  :description "An occupation"})
  (rel! ctx oid {:s "code:100" :p "skos:related" :t "occ:200"})
  ;; source B
  (mint! ctx oid {:uri "code:100" :label "Program One (source B)"
                  :description "The SAME program, as source B describes it"})
  (mint! ctx oid {:uri "inst:300" :label "Institution Three"
                  :description "An institution"})
  ;; edge endpoint occ:200_x is a NEAR-VARIANT of occ:200, not yet minted
  (rel! ctx oid {:s "inst:300" :p "skos:related" :t "occ:200_x"})
  {:src-a #{"code:100" "occ:200"}
   :src-b #{"code:100" "inst:300" "occ:200_x"}})

;; =============================================================================
;; 1. The pure shared-URI link detector (cross-source merge signal)
;; =============================================================================

(deftest shared-uri-links-flags-only-cross-source-resolved-uris
  (testing "A URI minted by ≥2 sources AND resolving in the current graph is a
            cross-source link; a single-source URI is not; a ≥2-source URI that
            does not resolve in the graph is not (no phantom link)."
    (let [result (dt/shared-uri-links
                  [{:source :a :uris #{"code:100" "occ:200"}}
                   {:source :b :uris #{"code:100" "inst:300" "occ:999"}}]
                  ;; occ:999 is claimed by source b but is NOT in the graph.
                  #{"code:100" "occ:200" "inst:300"})]
      (is (= ["code:100"] (:shared-uris result))
          "only the multi-source + resolving URI is a link")
      (is (= 1 (:shared-uri-count result)))
      (is (= #{:a :b} (get-in result [:by-uri "code:100"]))
          "the contributing sources are recorded")
      (is (nil? (get-in result [:by-uri "occ:999"]))
          "a multi-source URI absent from the graph is NOT reported as a link"))))

(deftest shared-uri-links-empty-when-no-sources
  (testing "No source-uri-sets → empty link report (the near-match + integrity
            passes still run; this is the report-only signal)."
    (let [r (dt/shared-uri-links nil #{"code:100"})]
      (is (= [] (:shared-uris r)))
      (is (= 0 (:shared-uri-count r))))))

;; =============================================================================
;; 2. LINK — shared canonical URI collapses to one node across sources
;; =============================================================================

(deftest cross-source-shared-uri-collapses-and-is-reported
  (testing "Two sources minting the SAME canonical URI resolve to ONE node, and
            reconcile reports it as a cross-source link contributed by both."
    (with-ctx [ctx]
      (let [oid (random-uuid)
            {:keys [src-a src-b]} (seed-two-sources! ctx oid)]
        ;; code:100 already collapsed in the projection (one key).
        (is (= 1 (count (filter #(= "code:100" (:uri %))
                                (rm/get-concepts ctx {:ontology-id oid}))))
            "shared canonical URI resolves to exactly one node")
        (let [r (dt/reconcile-graph! ctx {:ontology-id oid
                                          :source-uri-sets [{:source :a :uris src-a}
                                                            {:source :b :uris src-b}]})]
          (is (= :ok (:status r)))
          (is (= ["code:100"] (get-in r [:shared-uri-links :shared-uris]))
              "the shared canonical URI is reported as the cross-source link")
          (is (= #{:a :b} (get-in r [:shared-uri-links :by-uri "code:100"]))
              "both sources are credited with the link"))))))

;; =============================================================================
;; 3. SURFACE ambiguities — near-variant identity flagged, 0 dangling (V18)
;; =============================================================================

(deftest near-variant-endpoint-surfaced-as-ambiguity-and-no-dangling
  (testing "A cross-source edge to a near-variant of an existing URI: V18
            auto-mints the implied endpoint (0 dangling) AND surfaces the
            near-variant as an ambiguity — never silently merged or dropped."
    (with-ctx [ctx]
      (let [oid (random-uuid)
            {:keys [src-a src-b]} (seed-two-sources! ctx oid)
            r (dt/reconcile-graph! ctx {:ontology-id oid
                                        :source-uri-sets [{:source :a :uris src-a}
                                                          {:source :b :uris src-b}]})]
        ;; 0 dangling — V18 resolved the occ:200_x endpoint.
        (is (true? (get-in r [:referential-integrity :every-edge-endpoint-resolves?]))
            "every edge endpoint resolves after reconcile")
        (is (= 0 (get-in r [:referential-integrity :dangling-edge-count]))
            "0 dangling edges")
        (is (pos? (:implied-endpoints-minted r))
            "the dangling near-variant endpoint was auto-minted (implied)")
        ;; The near-variant is SURFACED as an ambiguity tying occ:200_x to occ:200.
        (is (pos? (:ambiguities-surfaced r)) "at least one ambiguity surfaced")
        (let [amb (first (filter #(= "occ:200_x" (:dangling-uri %)) (:ambiguities r)))]
          (is (some? amb) "the near-variant endpoint is surfaced as an ambiguity")
          (is (= "occ:200" (:near-existing-uri amb))
              "the ambiguity points at the existing near-variant URI"))))))

;; =============================================================================
;; 4. THE LOAD-BEARING SEAM — reconcile against current graph state,
;;    reconcile-NOT-duplicate (run twice / pre-populated graph)
;; =============================================================================

(deftest reconcile-twice-does-not-duplicate
  (testing "Running reconcile a SECOND time reconciles (merges / no-ops) — it
            does NOT duplicate. The concept count, shared-URI link, dangling
            count, and minted-implied count are stable across the second pass.
            This is the seam that makes the deferred maintain branch a clean
            later addition (it reads + reconciles against CURRENT graph state)."
    (with-ctx [ctx]
      (let [oid (random-uuid)
            {:keys [src-a src-b]} (seed-two-sources! ctx oid)
            sets [{:source :a :uris src-a} {:source :b :uris src-b}]
            r1 (dt/reconcile-graph! ctx {:ontology-id oid :source-uri-sets sets})
            count-after-1 (concept-count ctx oid)
            uris-after-1 (concept-uris ctx oid)
            r2 (dt/reconcile-graph! ctx {:ontology-id oid :source-uri-sets sets})
            count-after-2 (concept-count ctx oid)
            uris-after-2 (concept-uris ctx oid)]
        (is (= count-after-1 count-after-2)
            "the second pass did NOT grow the concept count (no duplicate mint)")
        (is (= uris-after-1 uris-after-2)
            "the concept URI set is identical after the second pass")
        (is (= (get-in r1 [:shared-uri-links :shared-uri-count])
               (get-in r2 [:shared-uri-links :shared-uri-count]))
            "the cross-source link is stable, not re-counted into a duplicate")
        (is (= 0 (get-in r2 [:referential-integrity :dangling-edge-count]))
            "still 0 dangling on the second pass")
        (is (= 0 (:implied-endpoints-minted r2))
            "the second pass mints NO new implied endpoints — they already resolve")))))

(deftest reconcile-against-prepopulated-graph-merges-not-duplicates
  (testing "Reconcile does NOT assume an empty graph. Pre-populate the graph,
            then a later source re-mints the SAME canonical URI: reconcile
            collapses into the existing node (one node, not two) and links it
            cross-source — the maintain-aware seam proven against a pre-existing
            graph rather than a second pass of the same run."
    (with-ctx [ctx]
      (let [oid (random-uuid)]
        ;; PRE-EXISTING graph (an earlier build): code:100 + occ:200 already there.
        (mint! ctx oid {:uri "code:100" :label "Program One (pre-existing)"
                        :description "Already in the graph from an earlier build"})
        (mint! ctx oid {:uri "occ:200" :label "Occupation Two" :description "occ"})
        (rel! ctx oid {:s "code:100" :p "skos:related" :t "occ:200"})
        (let [before (concept-count ctx oid)]
          ;; A NEW source arrives and re-mints the SAME canonical URI + a new one.
          (mint! ctx oid {:uri "code:100" :label "Program One (new source)"
                          :description "The same program from a new source"})
          (mint! ctx oid {:uri "inst:300" :label "Institution Three" :description "inst"})
          (let [after-mint (concept-count ctx oid)
                r (dt/reconcile-graph!
                   ctx {:ontology-id oid
                        :source-uri-sets [{:source :existing :uris #{"code:100" "occ:200"}}
                                          {:source :new :uris #{"code:100" "inst:300"}}]})]
            ;; Re-minting the shared URI did NOT create a second node.
            (is (= (inc before) after-mint)
                "re-mint of code:100 collapsed; only the genuinely-new inst:300 grew the graph")
            (is (= 1 (count (filter #(= "code:100" (:uri %))
                                    (rm/get-concepts ctx {:ontology-id oid}))))
                "the pre-existing node and the new mint are ONE node")
            (is (= ["code:100"] (get-in r [:shared-uri-links :shared-uris]))
                "the re-minted URI links the new source to the pre-existing graph")
            (is (= 0 (get-in r [:referential-integrity :dangling-edge-count]))
                "0 dangling against the pre-populated graph")))))))

;; =============================================================================
;; 5. Reuse-not-fork + domain-agnostic guards
;; =============================================================================

(deftest reconcile-registers-alignment-section-s03
  (testing "Reconcile registers an S03 alignment section for the ontology so
            cross-source equivalences (S08) have a DISTINCT section to record
            into (S08 Path-B forbids a self-section) — reusing the S03 registry
            command, not a forked store. The default section is a stable derived
            id, registered under the primary."
    (with-ctx [ctx]
      (let [oid (random-uuid)]
        (seed-two-sources! ctx oid)
        (let [r (dt/reconcile-graph! ctx {:ontology-id oid})
              expected-section (dt/default-alignment-section-id oid)]
          (is (= expected-section (:alignment-ontology-id r))
              "defaults to a DISTINCT stable derived alignment section, not the primary")
          (is (not= oid (:alignment-ontology-id r))
              "the alignment section is NOT the primary (S08 Path-B)")
          (is (contains? (rm/get-alignment-sections ctx oid) expected-section)
              "the alignment section is registered via the S03 command path")))))

  (testing "The default alignment-section id is STABLE across calls (idempotent —
            re-running targets the SAME section, not a fresh one)."
    (let [oid (random-uuid)]
      (is (= (dt/default-alignment-section-id oid)
             (dt/default-alignment-section-id oid))
          "deterministic derivation"))))

(deftest reconcile-requires-ontology-id
  (testing "Reconcile fails loudly without a granted scope (no silent empty-graph
            default — Discipline #5)."
    (with-ctx [ctx]
      (is (thrown? clojure.lang.ExceptionInfo
                   (dt/reconcile-graph! ctx {:ontology-id nil}))))))

(deftest reconcile-empty-graph-is-honest-noop
  (testing "Reconcile on an empty graph is an honest no-op: 0 links, 0 merges,
            0 ambiguities, 0 dangling — it does NOT fabricate anything. (The
            empty graph is a VALID input; the seam just has nothing to do.)"
    (with-ctx [ctx]
      (let [oid (random-uuid)
            r (dt/reconcile-graph! ctx {:ontology-id oid})]
        (is (= :ok (:status r)))
        (is (= 0 (:concepts-in-scope r)))
        (is (= 0 (get-in r [:shared-uri-links :shared-uri-count])))
        (is (= 0 (:merges r)))
        (is (= 0 (:ambiguities-surfaced r)))
        (is (= 0 (get-in r [:referential-integrity :dangling-edge-count])))))))
