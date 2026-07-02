(ns ai.obney.orc.ontology.mt4-occurrence-merge-test
  "MT-4 — within-source occurrence-merge (union attributes across containers).

   Drafts of the SAME real-world entity URI arriving from DIFFERENT containers
   (e.g. an entity's label+description from one container, a top-N summary
   attribute from a second, another summary from a third — all keyed by the
   same canonical URI) must collapse into ONE node carrying the UNION of their
   attributes. A same-key/different-value conflict is SURFACED, never silently
   overwritten.

   Tracers:
     1. `union-concept-drafts-by-uri` unions DISJOINT attributes (pure).
     2. A same-key/different-value CONFLICT is surfaced (not silent last-wins);
        label/description prefer the description-bearing draft.
     3. Landing through the PUBLIC `compile-discovery-source!` seam emits ONE
        create-concept per URI carrying the unioned attributes — asserted via
        the concepts PROJECTION read-back (no bare append), provenance surfaces
        the merge count.

   Domain/format-agnostic: the fixtures use neutral attribute keys and generic
   URIs (no O*NET/CIP/SOC column or entity baked in — Discipline #12)."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [ai.obney.orc.ontology.interface.schemas]
            [ai.obney.orc.ontology.core.commands]
            [ai.obney.orc.ontology.core.rlm-discovery :as rlm]
            [ai.obney.orc.ontology.core.read-models :as rm]
            [ai.obney.orc.ontology.interface :as ontology]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.query-processor.interface :as qp]
            [ai.obney.grain.pubsub.interface :as pubsub]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp]))

;; ===========================================================================
;; Test context (mirrors the v18 / s18 harness)
;; ===========================================================================

(defn- make-ctx []
  (rmp/l1-clear!)
  (let [ps (pubsub/start {:type :core-async :topic-fn :event/type})
        store (es/start {:conn {:type :in-memory} :event-pubsub ps :logger nil})
        dir (str "/tmp/mt4-test-" (random-uuid))
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

(defn- concepts-by-uri [ctx oid]
  (into {} (map (juxt :uri identity))
        (filter #(= oid (:ontology-id %)) (rm/get-concepts ctx {}))))

;; ===========================================================================
;; Tracer 1 — union of DISJOINT attributes (pure)
;; ===========================================================================
;; The O*NET-shaped case, domain-neutral: three same-URI drafts, each carrying a
;; DIFFERENT attribute key (an entity-defining draft with label+description, and
;; two measurement-container drafts each with a bare-code label). They must
;; collapse to ONE draft carrying ALL attributes + the real label/description.
;; A draft with a DIFFERENT URI stays separate.

(def ^:private disjoint-drafts
  [{:uri "entity/1" :label "Chief Widgeteer"
    :description "The entity as defined by its own container."
    :scope :custom :broader ["role/exec"] :indicators ["ind-a"]
    :attributes {:source-code "1"}}
   {:uri "entity/1" :label "1" :scope :custom
    :attributes {:top-skills ["Critical Thinking" "Active Listening"]}}
   {:uri "entity/1" :label "1" :scope :custom
    :attributes {:top-knowledge ["Administration" "English"]}}
   {:uri "entity/2" :label "Assistant Widgeteer"
    :description "A distinct entity."
    :scope :custom
    :attributes {:top-skills ["Speaking"]}}])

(deftest union-collapses-same-uri-drafts-and-unions-disjoint-attributes
  (testing "Three same-URI drafts with disjoint attribute keys collapse to ONE
            draft carrying label + description + all attributes; a different-URI
            draft stays separate. No conflicts."
    (let [{:keys [drafts conflicts groups-merged]}
          (rlm/union-concept-drafts-by-uri disjoint-drafts)
          by-uri (into {} (map (juxt :uri identity)) drafts)
          e1 (get by-uri "entity/1")
          e2 (get by-uri "entity/2")]
      (is (= 2 (count drafts)) "two distinct URIs -> two drafts")
      ;; The entity-defining draft's label/description win over the bare-code labels.
      (is (= "Chief Widgeteer" (:label e1)) "real label wins over bare-code labels")
      (is (= "The entity as defined by its own container." (:description e1))
          "the description-bearing draft's description wins")
      ;; Non-attribute fields preserved from the entity-defining draft.
      (is (= :custom (:scope e1)))
      (is (= ["role/exec"] (:broader e1)))
      (is (= ["ind-a"] (:indicators e1)))
      ;; The UNION — every attribute key present.
      (is (= "1" (get-in e1 [:attributes :source-code])))
      (is (= ["Critical Thinking" "Active Listening"]
             (get-in e1 [:attributes :top-skills])) "top-skills unioned in")
      (is (= ["Administration" "English"]
             (get-in e1 [:attributes :top-knowledge])) "top-knowledge unioned in")
      ;; No conflict, no review stamp.
      (is (empty? conflicts) "no conflicts on disjoint attributes")
      (is (not (get-in e1 [:attributes :requires-review?]))
          "no review stamp when there is no conflict")
      (is (= 1 groups-merged) "one URI group actually merged (>1 draft)")
      ;; The different-URI draft is untouched.
      (is (= "Assistant Widgeteer" (:label e2)))
      (is (= ["Speaking"] (get-in e2 [:attributes :top-skills]))))))

;; ===========================================================================
;; Tracer 2 — a same-key / different-value CONFLICT is surfaced (not silent)
;; ===========================================================================
;; Two same-URI drafts assert the SAME attribute key with DIFFERENT values. The
;; merge must NOT silently drop one (last-wins). It keeps one deterministically
;; AND surfaces the conflict (both the kept value + the alternative, and a
;; review stamp on the merged draft). Label/description prefer the
;; description-bearing draft.

(def ^:private conflict-drafts
  [;; a measurement container draft FIRST (bare-code label, one value)
   {:uri "entity/9" :label "9" :scope :custom
    :attributes {:head-count 100 :top-skills ["A"]}}
   ;; the entity-defining draft SECOND (real label+description, conflicting value)
   {:uri "entity/9" :label "Regional Widgeteer"
    :description "The defining container's account of the entity."
    :scope :custom
    :attributes {:head-count 250}}])

(deftest same-key-different-value-conflict-is-surfaced-not-silently-overwritten
  (testing "A same-URI, same-key, different-value clash is surfaced (kept value +
            alternatives + a :requires-review? stamp), never a silent last-wins.
            Label/description come from the description-bearing draft even though
            it is not first."
    (let [{:keys [drafts conflicts groups-merged]}
          (rlm/union-concept-drafts-by-uri conflict-drafts)
          e9 (first drafts)]
      (is (= 1 (count drafts)))
      (is (= 1 groups-merged))
      ;; Label/description prefer the description-bearing draft (2nd in order).
      (is (= "Regional Widgeteer" (:label e9))
          "the description-bearing draft's label wins even when not first")
      (is (= "The defining container's account of the entity." (:description e9)))
      ;; Deterministic keep: the defining draft carries :head-count -> its value.
      (is (= 250 (get-in e9 [:attributes :head-count])) "defining draft's value kept")
      ;; The non-conflicting key still unions in.
      (is (= ["A"] (get-in e9 [:attributes :top-skills])))
      ;; The conflict is SURFACED — not silently dropped.
      (is (= 1 (count conflicts)) "exactly one conflict surfaced")
      (let [c (first conflicts)]
        (is (= "entity/9" (:uri c)))
        (is (= :head-count (:key c)))
        (is (= 250 (:kept c)))
        (is (some #{100} (:alternatives c)) "the overwritten value is surfaced, not lost"))
      ;; The merged draft itself reads as needing review (projection-observable).
      (is (true? (get-in e9 [:attributes :requires-review?]))
          "merged draft stamped :requires-review? on conflict")
      (is (some #{100} (get-in e9 [:attributes :attribute-conflicts :head-count]))
          "the merged draft records the conflicting values")
      (is (some #{250} (get-in e9 [:attributes :attribute-conflicts :head-count]))))))

;; ===========================================================================
;; Tracer 3 — landing emits ONE create-concept per URI (through the PUBLIC seam)
;; ===========================================================================
;; Drive same-URI drafts through the PUBLIC `compile-discovery-source!` landing
;; seam against a real in-memory Grain event store; read the concepts PROJECTION
;; back. Exactly ONE node per URI, carrying the UNIONED attributes (no dropped
;; attribute, no duplicate node). Provenance surfaces the merge count. Asserted
;; via the projection — not a bare append (Discipline #7).

(def ^:private landing-output
  {:status :emitted-drafts
   :emitted-concepts
   ;; occupation-1: entity-defining draft + two measurement drafts (same URI)
   [{:uri "occ/1" :label "Chief Widgeteer"
     :description "The entity as defined by its own container."
     :scope :custom :attributes {:source-code "1"}}
    {:uri "occ/1" :label "1" :scope :custom
     :attributes {:top-skills ["Critical Thinking" "Active Listening"]}}
    {:uri "occ/1" :label "1" :scope :custom
     :attributes {:top-knowledge ["Administration" "English"]}}
    ;; occupation-2: single draft (control — must be unaffected)
    {:uri "occ/2" :label "Assistant Widgeteer"
     :description "A distinct entity." :scope :custom
     :attributes {:source-code "2" :top-skills ["Speaking"]}}]
   :emitted-relationships []
   :emitted-axioms []
   :rlm-trace []
   :patterns-offered 5})

(deftest landing-emits-one-concept-per-uri-with-unioned-attributes
  (testing "Same-URI drafts land as ONE projection node per URI carrying the
            UNION of their attributes (label + description + both summaries) —
            no dropped attribute, no duplicate node. Provenance surfaces the
            merge count."
    (with-ctx [ctx]
      (let [oid (random-uuid)
            stub (ontology/compile-discovery-source! ctx oid landing-output)
            by-uri (concepts-by-uri ctx oid)
            occ1 (get by-uri "occ/1")
            occ2 (get by-uri "occ/2")]
        ;; ONE node per URI (no duplicates): 4 drafts -> 2 concepts.
        (is (= 2 (count by-uri)) "two distinct URIs -> two projection nodes")
        (is (= 2 (get-in stub [:discovery-provenance :concepts-emitted]))
            "exactly two create-concept events landed (union applied pre-landing)")
        ;; occ/1 carries the UNION — the O*NET drop bug is fixed.
        (is (= "Chief Widgeteer" (:label occ1)) "real label survived, not the bare code")
        (is (not (str/blank? (:description occ1))) "description survived")
        (is (= "1" (get-in occ1 [:attributes :source-code])))
        (is (= ["Critical Thinking" "Active Listening"]
               (get-in occ1 [:attributes :top-skills]))
            "top-skills NOT dropped (the MT-4 bug)")
        (is (= ["Administration" "English"]
               (get-in occ1 [:attributes :top-knowledge]))
            "top-knowledge present too")
        ;; Provenance surfaces the union summary.
        (is (= 1 (get-in stub [:discovery-provenance :occurrence-groups-merged]))
            "one URI group merged (>1 draft)")
        (is (= 0 (get-in stub [:discovery-provenance :occurrence-conflicts]))
            "no conflicts in this disjoint slice")
        ;; The single-draft control is unaffected.
        (is (= "Assistant Widgeteer" (:label occ2)))
        (is (= ["Speaking"] (get-in occ2 [:attributes :top-skills])))))))

;; ===========================================================================
;; MT-4b — CROSS-BATCH occurrence-merge: a draft whose URI matches a concept
;; ALREADY LANDED by a PRIOR reconcile/landing call (a LATER source enriching an
;; entity an EARLIER source created). The URI-keyed read-model REPLACE means the
;; second landing clobbers the first unless we fold the EXISTING projection
;; concept into the union before re-emitting. This is the 5-source-build case.
;; ===========================================================================

(deftest union-drafts-with-existing-folds-the-current-projection-concept
  (testing "union-drafts-with-existing unions each incoming same-URI draft WITH the
            existing landed concept for that URI: the existing label/description +
            attributes are preserved and the incoming attributes are added; a URI
            with NO existing concept passes through unchanged (pure)."
    (let [existing-by-uri
          {"occ/1" {:uri "occ/1" :label "Chief Widgeteer"
                    :description "Existing definition." :scope :custom
                    :attributes {:source-code "1" :top-skills ["Critical Thinking"]}}}
          incoming [{:uri "occ/1" :label "1" :scope :custom      ; a LATER source, bare label
                     :attributes {:top-knowledge ["Administration"]}}
                    {:uri "occ/9" :label "New Entity" :description "brand new" :scope :custom
                     :attributes {:top-skills ["Speaking"]}}]     ; no existing → passthrough
          {:keys [drafts groups-merged]} (rlm/union-drafts-with-existing incoming existing-by-uri)
          by-uri (into {} (map (juxt :uri identity)) drafts)
          o1 (get by-uri "occ/1")
          o9 (get by-uri "occ/9")]
      ;; occ/1 = existing ⊕ incoming
      (is (= "Chief Widgeteer" (:label o1)) "existing entity-defining label preserved")
      (is (= "Existing definition." (:description o1)) "existing description preserved")
      (is (= ["Critical Thinking"] (get-in o1 [:attributes :top-skills])) "existing attr preserved")
      (is (= ["Administration"] (get-in o1 [:attributes :top-knowledge])) "incoming attr added")
      (is (= "1" (get-in o1 [:attributes :source-code])) "existing key-value preserved")
      (is (= 1 groups-merged) "the one URI with an existing concept counts as merged")
      ;; occ/9 has no existing concept → untouched
      (is (= "New Entity" (:label o9)))
      (is (= ["Speaking"] (get-in o9 [:attributes :top-skills]))))))

(defn- land! [ctx oid concepts]
  (ontology/compile-discovery-source!
   ctx oid {:status :emitted-drafts :emitted-concepts concepts
            :emitted-relationships [] :emitted-axioms [] :rlm-trace []}))

(deftest cross-batch-landing-unions-onto-the-existing-node-not-replace
  (testing "A second landing call for a URI already in the graph UNIONS onto the
            existing node (label + description + prior attrs preserved, new attr
            added) rather than REPLACING it — the 5-source cross-source enrichment
            case. Asserted via the projection read-back."
    (with-ctx [ctx]
      (let [oid (random-uuid)
            ;; batch 1 (source A): the entity-defining concept + a summary attribute
            _ (land! ctx oid [{:uri "occ/1" :label "Chief Widgeteer"
                               :description "Determine and formulate policies."
                               :scope :custom
                               :attributes {:source-code "1"
                                            :top-skills ["Critical Thinking" "Active Listening"]}}])
            after1 (get (concepts-by-uri ctx oid) "occ/1")
            ;; batch 2 (source B, a SEPARATE landing call): same URI, a bare label,
            ;; a NEW attribute the earlier source didn't have.
            stub2 (land! ctx oid [{:uri "occ/1" :label "1" :scope :custom
                                   :attributes {:top-knowledge ["Administration" "English"]}}])
            after2 (get (concepts-by-uri ctx oid) "occ/1")
            all (concepts-by-uri ctx oid)]
        ;; batch 1 landed cleanly
        (is (= "Chief Widgeteer" (:label after1)))
        (is (= ["Critical Thinking" "Active Listening"] (get-in after1 [:attributes :top-skills])))
        ;; still ONE node for the URI (no duplicate)
        (is (= 1 (count all)) "still exactly one node for the URI after the second landing")
        ;; the UNION held across the two separate landings (the cross-batch fix)
        (is (= "Chief Widgeteer" (:label after2))
            "the existing entity-defining label survived the second source (NOT clobbered to the code)")
        (is (not (str/blank? (:description after2))) "the existing description survived")
        (is (= ["Critical Thinking" "Active Listening"] (get-in after2 [:attributes :top-skills]))
            "the prior source's top-skills survived (NOT dropped by the replace)")
        (is (= ["Administration" "English"] (get-in after2 [:attributes :top-knowledge]))
            "the later source's top-knowledge was added")
        ;; provenance: the second landing surfaces the cross-batch merge
        (is (= 1 (get-in stub2 [:discovery-provenance :occurrence-groups-merged]))
            "the second landing reports the URI merged onto an existing node")))))
