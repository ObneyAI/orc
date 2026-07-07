(ns ai.obney.orc.ontology.connect3a-associative-fold-test
  "CONNECT-3a — the associative-junction fold (element nodes + occupation→element edges).

   The load-bearing connectivity fix. An observation build PROVED that junction sheets
   (SOC + Element + rating) aggregate onto occupations as attribute LISTS → 0 edges,
   0 element nodes, a BFS-DEAD graph. This slice adds a DETERMINISTIC ASSOCIATION mode
   to the SAME `container-aggregate` fold (no fork): when the spec carries a `:predicate`
   + `:element-entity-type` (in place of `:attr-name`), `aggregate-finalize` emits
   DISTINCT SHARED element concept-drafts (canonical URI, deduped — one skill node
   shared across occupations) + ONE key→element relationship-draft per (key,element)
   pair (the value-col rating rides the edge). Both flow through the SAME landing.

   Domain-agnostic (#12): the spec names NO O*NET/SOC column — every column, the
   predicate, and the entity-types come from the runtime spec. Deterministic — NO LLM
   here (the fold is driven with a hand-built spec; model-authoring is CONNECT-3b).

   Tracers:
     1. associative finalize → nodes + edges on a Skills-shaped fixture
     2. canonical dedup — a skill shared by 3 occupations = ONE node, THREE edges
     3. the edge carries the rating in :attributes
     4. attribute (collect/top-N) modes are byte-identical (association is opt-in)
     5. THE LOAD-BEARING one — land occupations + the fold's drafts+edges into a REAL
        store, build-concept-graph + expand-concept-neighborhood (BFS): occupation A
        reaches skill S reaches occupation B (a DIFFERENT occupation sharing S)."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.obney.orc.ontology.core.container-aggregate :as ca]
            [ai.obney.orc.ontology.test-helpers :as h]
            ;; event-schema registration — without it the store's append-time Malli
            ;; validation rejects concept-created / relationship-created and writes no-op.
            [ai.obney.orc.ontology.interface.schemas]
            [ai.obney.orc.ontology.core.commands :as cmd]
            [ai.obney.orc.ontology.core.retrieval :as retrieval]))

;; ===========================================================================
;; A Skills-shaped junction fixture — {SOC, Element, DataValue} for a few
;; occupations sharing some skills. `skillShared` is required by ALL THREE
;; occupations (the dedup + BFS-bridge skill); `skillA`/`skillB` are unique.
;; Domain-neutral abstract labels — no real O*NET column/skill baked in.
;; ===========================================================================

(def ^:private junction-rows
  [{"SOC" "occA" "Element" "skillShared" "DataValue" 5}
   {"SOC" "occA" "Element" "skillA"      "DataValue" 4}
   {"SOC" "occB" "Element" "skillShared" "DataValue" 3}
   {"SOC" "occB" "Element" "skillB"      "DataValue" 2}
   {"SOC" "occC" "Element" "skillShared" "DataValue" 1}])

(def ^:private association-spec
  "The ASSOCIATION spec — `:predicate` + `:element-entity-type` in place of `:attr-name`.
   These two fields TRIGGER the association finalize branch."
  {:key-col "SOC" :element-col "Element" :value-col "DataValue"
   :predicate "requires" :element-entity-type "skill" :key-entity-type "occupation"})

(def ^:private association-spec-model-shape
  "CONNECT-3c — the spec shape the MODEL actually authors (CONNECT-3b prototype
   proved it emits `:entity-type`, NOT `:key-entity-type`). The source node must key
   off `:entity-type` so edges attach to the canonical `occupation/<key>` nodes."
  {:key-col "SOC" :element-col "Element" :value-col "DataValue"
   :predicate "requires" :element-entity-type "skill" :entity-type "occupation"})

(deftest connect3c-source-keys-off-entity-type-when-key-entity-type-absent
  (testing "CONNECT-3c — with the model's real spec shape (:entity-type, NO
            :key-entity-type), edge source-uris are occupation/<key> (the CANONICAL
            scheme), NOT entity/<key> stubs — so edges attach to the rich canonical
            occupation nodes (the fragmentation gap CONNECT-3b flagged)"
    (let [{:keys [relationship-drafts]}
          (ca/stream-aggregate association-spec-model-shape junction-rows)
          src-schemes (set (map #(namespace (keyword (:source-uri %))) relationship-drafts))]
      (is (= #{"occupation"} src-schemes)
          "source keys off :entity-type → occupation/<key>, the canonical scheme")
      (is (not (contains? src-schemes "entity"))
          "NO entity/<key> stubs — the fragmentation CONNECT-3c removes"))))

;; ===========================================================================
;; Tracer 1 — associative finalize → element NODES + occupation→element EDGES.
;; ===========================================================================

(deftest associative-finalize-emits-element-nodes-and-edges
  (testing "an association spec (with :predicate + :element-entity-type) folds a
            SOC→Element junction into DISTINCT shared element concept-drafts + one
            occupation→element relationship-draft per (key,element) row — NOT an
            occupation attribute list (which is 0 edges / 0 element nodes / BFS-dead)"
    (let [{:keys [concept-drafts relationship-drafts distinct-keys]}
          (ca/stream-aggregate association-spec junction-rows)
          skills (set (map :label concept-drafts))]
      ;; ELEMENT NODES: one DISTINCT element concept per skill value (3 distinct
      ;; skills across the 5 rows), each a shared canonical node with :entity-type "skill".
      (is (= 3 (count concept-drafts))
          "one concept-draft per DISTINCT element value (shared skill node), not per row")
      (is (= #{"skillShared" "skillA" "skillB"} skills)
          "the element nodes are the distinct skill labels")
      (is (every? #(= "skill" (:entity-type %)) concept-drafts)
          "every element node carries the :element-entity-type")
      (is (contains? (set (map :uri concept-drafts)) "skill/skillShared")
          "the element node carries a canonical URI keyed on the element value")
      ;; EDGES: one occupation→skill edge per raw (key,element) row (5 rows → 5 edges).
      (is (= 5 (count relationship-drafts))
          "one occupation→element edge per (key,element) row (5 rows → 5 edges)")
      (is (every? #(= "requires" (:predicate %)) relationship-drafts)
          "every edge carries the spec's :predicate")
      (is (= #{"occupation/occA" "occupation/occB" "occupation/occC"}
             (set (map :source-uri relationship-drafts)))
          "edge :source-uri is the KEY (occupation) canonical URI")
      (is (every? #(= "skill" (namespace (keyword (:target-uri %)))) relationship-drafts)
          "edge :target-uri points at the element (skill) node")
      ;; the key count is honest (3 occupations produced edges)
      (is (= 3 distinct-keys)))))

;; ===========================================================================
;; Tracer 2 — canonical dedup: a skill shared by 3 occupations = ONE node, THREE
;; edges (NOT 3 skill nodes — the over-mint we are avoiding).
;; ===========================================================================

(deftest shared-element-is-one-canonical-node-with-n-edges
  (testing "`skillShared` is required by occA, occB AND occC — it must collapse to
            ONE canonical shared concept-draft (not three per-occurrence nodes) while
            producing THREE distinct occupation→skill edges"
    (let [{:keys [concept-drafts relationship-drafts]}
          (ca/stream-aggregate association-spec junction-rows)
          shared-nodes (filter #(= "skillShared" (:label %)) concept-drafts)
          shared-edges (filter #(= "skill/skillShared" (:target-uri %)) relationship-drafts)]
      ;; ONE node for the shared skill (canonical dedup — the anti-over-mint invariant)
      (is (= 1 (count shared-nodes))
          "a skill shared by 3 occupations is ONE canonical node (deduped), not 3")
      (is (= "skill/skillShared" (:uri (first shared-nodes)))
          "the single shared node has the canonical element URI")
      ;; THREE edges into that ONE node — occupation↔skill fan-in is via edges, not nodes
      (is (= 3 (count shared-edges))
          "the shared skill has THREE occupation→skill edges (one per occupation)")
      (is (= #{"occupation/occA" "occupation/occB" "occupation/occC"}
             (set (map :source-uri shared-edges)))
          "the three edges originate from the three distinct occupations"))))

;; ===========================================================================
;; Tracer 3 — the edge carries the value-col rating in keyword-keyed :properties.
;; CONNECT-3b moved the rating from the string-keyed :attributes (dropped by the
;; create-relationship compile path) to keyword-keyed :properties (forwarded).
;; ===========================================================================

(deftest edge-carries-the-value-col-rating-in-properties
  (testing "the numeric rating (the :value-col value) rides the occupation→skill EDGE
            under the value-col name as a KEYWORD in :properties (CONNECT-3b — so it
            survives relationship-draft->command into the landed edge)"
    (let [{:keys [relationship-drafts]} (ca/stream-aggregate association-spec junction-rows)
          edge (fn [src tgt]
                 (first (filter #(and (= src (:source-uri %)) (= tgt (:target-uri %)))
                                relationship-drafts)))]
      (is (= {(keyword "DataValue") 5} (:properties (edge "occupation/occA" "skill/skillShared")))
          "the occA→skillShared edge carries its rating (5) in keyword-keyed :properties")
      (is (= {(keyword "DataValue") 3} (:properties (edge "occupation/occB" "skill/skillShared")))
          "the occB→skillShared edge carries its own rating (3)")
      (is (= {(keyword "DataValue") 1} (:properties (edge "occupation/occC" "skill/skillShared")))
          "the occC→skillShared edge carries its own rating (1)")
      (is (nil? (:attributes (edge "occupation/occA" "skill/skillShared")))
          "the rating no longer rides :attributes (that string-keyed map was dropped)"))))

;; ===========================================================================
;; Tracer 4 — the collect/top-N ATTRIBUTE modes are byte-identical: association is
;; OPT-IN via :predicate/:element-entity-type; a spec WITHOUT them is unchanged
;; (one attribute draft per key, NO relationship-drafts).
;; ===========================================================================

(deftest attribute-modes-unchanged-when-not-an-association-spec
  (testing "a spec with :attr-name (NO :predicate/:element-entity-type) still produces
            the collect/top-N attribute draft byte-identical — one draft per key with
            the flat attribute list AND ZERO relationship-drafts (association is opt-in)"
    ;; top-N attribute spec (has :value-col + :attr-name, NO :predicate) — unchanged
    (let [attr-spec {:key-col "SOC" :element-col "Element" :value-col "DataValue"
                     :attr-name :topSkills :entity-type "occupation"}
          {:keys [concept-drafts relationship-drafts distinct-keys]}
          (ca/stream-aggregate attr-spec junction-rows)
          by-uri (into {} (map (juxt :uri identity)) concept-drafts)]
      (is (= 3 (count concept-drafts))
          "one attribute draft per occupation KEY (the existing top-N rollup)")
      (is (= 3 distinct-keys))
      ;; occA rolls its skills up into ONE attribute list (ranked by value) — the OLD
      ;; behavior that produces 0 edges / 0 element nodes.
      (is (= ["skillShared" "skillA"]
             (get-in by-uri ["occupation/occA" :attributes :topSkills]))
          "the occupation carries its skills as a flat ranked attribute list (unchanged)")
      (is (= "occupation" (:entity-type (first concept-drafts)))
          "the draft is the KEY entity (occupation), not the element")
      ;; the association opt-in did NOT fire — NO edges emitted for an attribute spec
      (is (empty? relationship-drafts)
          "an attribute spec (no :predicate) emits ZERO relationship-drafts (byte-identical)"))
    ;; collect attribute spec (NO :value-col, NO :predicate) — also unchanged
    (let [collect-spec {:key-col "SOC" :element-col "Element"
                        :attr-name :skillList :entity-type "occupation"}
          {:keys [concept-drafts relationship-drafts]}
          (ca/stream-aggregate collect-spec junction-rows)
          by-uri (into {} (map (juxt :uri identity)) concept-drafts)]
      (is (= ["skillShared" "skillA"]
             (get-in by-uri ["occupation/occA" :attributes :skillList]))
          "collect mode still folds elements into ONE per-key flat list (unchanged)")
      (is (empty? relationship-drafts)
          "collect mode emits ZERO relationship-drafts (association is opt-in)"))))

;; ===========================================================================
;; Tracer 5 — THE LOAD-BEARING PROOF. Land the fixture's occupations + the associative
;; fold's element concept-drafts + relationship-drafts into a REAL in-memory store, then
;; build the concept graph (REAL build-concept-graph) and run BFS (REAL
;; expand-concept-neighborhood): occupation A reaches skill S reaches occupation B.
;; A graph you can't traverse is NOT the fix — BFS traversal is the gate, not "edges exist".
;; ===========================================================================

;; A concrete section id — the create-concept command tags every event
;; `[:ontology <id>]` and the store's Malli rejects a nil id (a silent no-op),
;; so a REAL uuid must be threaded through both concept + relationship landing.
(def ^:private ontology-id #uuid "c3a00000-0000-0000-0000-00000000c3a0")

(defn- land-concept!
  [ctx uri label]
  (h/run-and-apply! ctx
                    (fn [c]
                      (cmd/ontology-create-concept
                       (assoc c :command
                              (h/make-concept-data :ontology-id ontology-id
                                                   :uri uri :label label
                                                   :description label :scope :custom))))))

(defn- land-concept-draft!
  [ctx draft]
  (land-concept! ctx (:uri draft) (:label draft)))

(defn- land-relationship-draft!
  [ctx draft]
  ;; Mirror the REAL `relationship-draft->command` compile path: it lands
  ;; source/target/predicate + `:properties (or (:properties draft) {})`. CONNECT-3b:
  ;; the association drafts now carry the rating under keyword-keyed `:properties`
  ;; (the value-col name as a keyword), so it SURVIVES into the landed edge (the
  ;; relationship-created `:properties` schema is [:map-of :keyword :any]). BFS
  ;; traversal below needs only the EDGE; the rating-survives-landing proof lives in
  ;; the CONNECT-3b test.
  (h/run-and-apply! ctx
                    (fn [c]
                      (cmd/ontology-create-relationship
                       (assoc c :command
                              {:ontology-id ontology-id
                               :source-uri (:source-uri draft)
                               :target-uri (:target-uri draft)
                               :predicate (:predicate draft)
                               :properties (or (:properties draft) {})})))))

(deftest bfs-traversal-occupation-to-skill-to-occupation
  (testing "THE LOAD-BEARING PROOF — after landing the occupations + the associative
            fold's element nodes + occupation→skill edges into a REAL store, BFS from
            occupation A reaches the shared skill S AND reaches a DIFFERENT occupation
            B sharing S. This is occupation↔skill↔occupation traversal — the graph
            that was BFS-DEAD (0 edges) before is now traversable."
    (h/with-test-context [ctx]
      (let [{:keys [concept-drafts relationship-drafts]}
            (ca/stream-aggregate association-spec junction-rows)]
        ;; land the KEY entities (occupations) — from the occupation sheet, not the fold
        (land-concept! ctx "occupation/occA" "Occupation A")
        (land-concept! ctx "occupation/occB" "Occupation B")
        (land-concept! ctx "occupation/occC" "Occupation C")
        ;; land the fold's SHARED element nodes (skills) + occupation→skill edges
        (doseq [d concept-drafts] (land-concept-draft! ctx d))
        (doseq [r relationship-drafts] (land-relationship-draft! ctx r))
        ;; build the REAL concept graph over the REAL store, then BFS from occupation A
        (let [graph (retrieval/build-concept-graph ctx)
              reached (->> (retrieval/expand-concept-neighborhood
                            ["occupation/occA"] :graph graph :max-depth 3)
                           (map :uri)
                           set)]
          ;; occupation A → skill S (the shared skill is reachable via the edge)
          (is (contains? reached "skill/skillShared")
              "BFS from occupation A reaches the shared skill S (occupation→skill edge)")
          ;; skill S → occupation B (a DIFFERENT occupation sharing S) — the bridge
          (is (contains? reached "occupation/occB")
              "BFS from occupation A reaches occupation B THROUGH the shared skill S")
          (is (contains? reached "occupation/occC")
              "BFS also reaches occupation C through the same shared skill S")
          ;; the whole point: occupation↔skill↔occupation is now a connected component
          (is (and (contains? reached "skill/skillShared")
                   (contains? reached "occupation/occB"))
              "occupation A ↔ skill S ↔ occupation B is traversable (was BFS-dead: 0 edges)"))))))
