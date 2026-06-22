(ns ai.obney.orc.ontology.axiom-tbox-subbehavior-test
  "EB6 — the AXIOM/TBox subbehavior as a delegatable ORC sheet.

   These durable, HERMETIC tests lock the load-bearing STRUCTURE + CONTRACT + the
   honest-gap behavior the EB6 prototype/live-verify validated, through the
   subbehavior's PUBLIC surface (its registry name, its `:reads`/`:writes`
   contract + blackboard schema, its persisted node config) AND through the
   emission orchestration's PUBLIC contract — its grounded S07 emission + the
   read-back proof (discipline 7) + every honest-gap bucket. They are hermetic:
   axiom emission is COMMAND-ONLY (no LLM, no ColBERT, no embeddings), so EB6's
   real behavior is fully provable in the fast brick gate — UNLIKE EB4/EB5 whose
   live verify drove extraction/embedding/ColBERT and moved to the on-demand lane.

   What is locked here:
     - Registry: name → deterministic sheet-id, idempotent re-registration; the
       Axiom/TBox sheet is SOURCE-AGNOSTIC (one sheet for every candidate set +
       graph), like EB3-EB5 — it bakes in no path.
     - Node design: a SINGLE `:code` node (deterministic coercion); NO `:llm`
       node, NO `:repl-researcher`, NO `:rlm` config.
     - The public contract: [:ontology-id :candidate-axioms] in, [:axiom-report]
       out; the report write declares a STRUCTURED schema (not a bare :map).
     - REUSE not fork: the kind→family table reuses the V07 vocabulary
       normalization; the four S07 commands + the EB6-minted assert-sub-class are
       emitted (no forked axiom command).
     - The HONEST-GAP rule (load-bearing — EB6 closes a silent-drop bug): a
       supported kind grounded against the REAL graph LANDS (read back via
       get-axioms); an ungrounded ref is surfaced (not asserted); an unsupported
       kind (domain/range/closure) is surfaced (not silently skipped); a
       command-rejected body is surfaced loudly.
     - The EB6 MINT: assert-sub-class lands a real rdfs:subClassOf axiom that was
       previously a pathless silent drop.
     - Domain-agnostic (#12): no vertical vocab; axioms come from the runtime
       candidates + graph."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.obney.orc.ontology.test-helpers :as h]
            [ai.obney.orc.orc-service.core.read-models :as orm]
            [ai.obney.orc.ontology.interface.schemas]
            [ai.obney.orc.ontology.core.commands :as cmd]
            [ai.obney.orc.ontology.core.read-models :as rm]
            [ai.obney.orc.ontology.core.serialization :as serialization]
            [ai.obney.orc.ontology.core.axiom-tbox-subbehavior :as eb6]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.time.interface :as time]
            [malli.core :as m]
            [clojure.string :as str]
            [cognitect.anomalies :as anom]))

(def ontology-id #uuid "eb600000-0000-0000-0000-000000000001")

;; ---------------------------------------------------------------------------
;; Fixture — a small REAL graph (concepts + a relationship), landed via commands.
;; Domain-neutral animals (no vertical vocab).
;; ---------------------------------------------------------------------------

(defn- create-concept! [ctx body]
  (h/run-and-apply! ctx (fn [c] (cmd/ontology-create-concept (assoc c :command body)))))

(defn- create-relationship! [ctx s p t]
  (h/run-and-apply! ctx (fn [c] (cmd/ontology-create-relationship
                                 (assoc c :command {:source-uri s :target-uri t
                                                    :predicate p :properties {}})))))

(defn- seed-graph! [ctx]
  (doseq [b [{:ontology-id ontology-id :uri "entity:dog" :label "Dog"
              :description "A dog" :scope :custom}
             {:ontology-id ontology-id :uri "entity:cat" :label "Cat"
              :description "A cat" :scope :custom}
             {:ontology-id ontology-id :uri "entity:animal" :label "Animal"
              :description "An animal" :scope :custom}]]
    (create-concept! ctx b))
  (create-relationship! ctx "entity:dog" "hasOwner" "entity:cat"))

;; A REAL-shaped EB3 candidate-axioms map (tolerant kinds; refs by URI + label).
(def candidate-axioms
  {:axioms
   [{:kind :disjoint :classes ["Dog" "Cat"]
     :rationale "no animal is both a dog and a cat"}
    {:kind :functional :predicate "hasOwner"
     :rationale "an animal has at most one owner"}
    {:kind :sub-class :sub-class "entity:dog" :super-class "entity:animal"
     :rationale "a dog is an animal"}
    ;; a tracked GAP kind — must surface, not silently skip
    {:kind :domain :predicate "hasOwner" :class "Dog"
     :rationale "domain of hasOwner is Dog"}
    ;; an UNGROUNDED disjointness — refs that do NOT resolve in the graph
    {:kind :disjoint :classes ["Unicorn" "Dragon"]
     :rationale "made up — not in the graph"}]})

;; =============================================================================
;; Registry: name → deterministic sheet-id, idempotent, source-agnostic.
;; =============================================================================

(deftest registry-name-resolves-to-deterministic-idempotent-sheet-id-test
  (testing "the Axiom/TBox subbehavior registers by name → a deterministic, idempotent sheet-id"
    (h/with-test-context [ctx]
      (let [ctx (assoc ctx :command-registry (cp/global-command-registry))
            id-1 (eb6/register-axiom-tbox-subbehavior! ctx {})
            looked-up (eb6/axiom-tbox-sheet-id-for)
            id-2 (eb6/register-axiom-tbox-subbehavior! ctx {})]
        (is (= id-1 looked-up)
            "name→sheet-id lookup must match the registered sheet-id")
        (is (= id-1 id-2)
            "re-registering an unchanged subbehavior is idempotent (same id)")))))

(deftest axiom-tbox-subbehavior-is-source-agnostic-test
  (testing "ONE Axiom/TBox sheet serves every candidate set + graph (it grounds the
            candidates against the graph for the :ontology-id at runtime) — like EB3-EB5"
    (is (= "ontology-axiom-tbox/axiom-tbox@v1" (eb6/axiom-tbox-subbehavior-name))
        "the Axiom/TBox registry name carries no source/medium/path tag")))

;; =============================================================================
;; Node design: a SINGLE :code node (deterministic coercion).
;; =============================================================================

(deftest body-is-a-single-code-node-no-llm-test
  (testing "the Axiom/TBox body is ONE :code node — deterministic coercion, NO :llm"
    (h/with-test-context [ctx]
      (let [ctx (assoc ctx :command-registry (cp/global-command-registry))
            sid (eb6/register-axiom-tbox-subbehavior! ctx {})
            nodes (vals (orm/get-nodes-by-id ctx sid))
            leaves (filter #(= :leaf (:type %)) nodes)
            code-leaves (filter #(= :code (:executor %)) leaves)
            llm-leaves (filter #(= :ai (:executor %)) leaves)]
        (is (= 1 (count code-leaves)) "exactly one :code leaf (emit-axioms)")
        (is (empty? llm-leaves) "no :ai (:llm) leaf — EB3 already did the reasoning")
        (is (empty? (filter #(= :repl-researcher (:type %)) nodes))
            "no :repl-researcher node")
        (is (nil? (some #(get % :rlm) nodes))
            "no :rlm config — Axiom/TBox is a deterministic coercion")
        (is (contains? (set (map :fn code-leaves))
                       "ai.obney.orc.ontology.core.axiom-tbox-subbehavior/emit-axioms-code")
            "the :code node's :fn is the emit-axioms wrapper")))))

(deftest node-contract-reads-and-writes-test
  (testing "contract: the :code node reads [:ontology-id :candidate-axioms] and
            writes [:axiom-report]"
    (h/with-test-context [ctx]
      (let [ctx (assoc ctx :command-registry (cp/global-command-registry))
            node (first (filter #(= :code (:executor %))
                                (vals (orm/get-nodes-by-id
                                       ctx (eb6/register-axiom-tbox-subbehavior! ctx {})))))]
        (is (= [:ontology-id :candidate-axioms :model-spec] (vec (:reads node))))
        (is (= [:axiom-report] (vec (:writes node))))))))

(deftest report-write-schema-is-structured-not-bare-map-test
  (testing "the :axiom-report write declares a STRUCTURED [:map …] schema (not a
            bare :map) and validates a real report shape"
    (is (= :map (first eb6/axiom-report-schema)))
    (is (> (count eb6/axiom-report-schema) 2)
        "a STRUCTURED [:map …] has field entries — a bare :map would not")
    (is (m/validate eb6/axiom-report-schema
                    {:ontology-id ontology-id :candidates-considered 5
                     :axioms-emitted [] :axioms-emitted-count 0
                     :axioms-ungrounded [] :axioms-unsupported [] :axioms-rejected []
                     :axioms-read-back nil}))
    (is (not (m/validate eb6/axiom-report-schema "a json string"))
        "a STRING does not validate the structured map schema")))

;; =============================================================================
;; The HONEST-GAP rule + the read-back proof (the load-bearing EB6 behavior).
;; =============================================================================

(deftest supported-grounded-kinds-land-and-read-back-test
  (testing "LOAD-BEARING: disjointness + property-characteristic + sub-class,
            GROUNDED against the real graph, are EMITTED and LAND — proven by
            reading the axioms BACK from the projection (discipline 7), NOT by a
            return value. Refs by LABEL ground to URIs; refs by URI pass through."
    (h/with-test-context [ctx]
      (seed-graph! ctx)
      (let [report (eb6/emit-axioms! ctx {:ontology-id ontology-id
                                          :candidate-axioms candidate-axioms})
            ;; READ BACK independently of the report (discipline 7 — projection truth)
            axioms (rm/get-axioms ctx ontology-id)]
        ;; 3 of the 5 candidates are supported + grounded
        (is (= 3 (:axioms-emitted-count report)))
        ;; disjointness LANDED — Dog/Cat labels grounded to their URIs, symmetric
        (is (= #{"entity:cat"} (get-in axioms [:disjointness "entity:dog"]))
            "disjointness landed in projection (Dog→Cat by grounded URI)")
        (is (= #{"entity:dog"} (get-in axioms [:disjointness "entity:cat"]))
            "disjointness is symmetric (Cat→Dog)")
        ;; characteristic LANDED — predicate grounded against the real relationship
        (is (contains? (get-in axioms [:characteristics "hasOwner"] #{}) :functional)
            "hasOwner marked functional in projection")
        ;; sub-class LANDED — the EB6 MINT path
        (is (= "entity:animal" (get-in axioms [:sub-class-of "entity:dog"]))
            "sub-class landed in projection (dog subClassOf animal) — the EB6 mint")))))

(deftest entity-type-and-keying-field-refs-ground-via-class-layer-and-model-spec-test
  (testing "LOAD-BEARING (the EB6 live-verify root-cause fix): EB3's REAL
            candidates reference entity TYPES (`:types`) and URI-keying FIELDS
            (`:field`), NOT instance URIs. These ground against the REAL class
            layer (concepts' `:broader` SKOS classes = the entity types) and the
            model-spec's URI-keying fields — so a `:disjoint` over types + a
            `:functional` over a keying field LAND, instead of falling through to
            ungrounded (the 0-emitted live-verify failure mode)."
    (h/with-test-context [ctx]
      ;; a graph whose instances carry :broader = their entity TYPE (how EB4
      ;; represents the class layer), like the real EB4-landed crosswalk graph.
      (create-concept! ctx {:ontology-id ontology-id :uri "program:01" :label "Agriculture"
                            :scope :custom :broader ["ProgramOfStudy"]
                            :attributes {:code "01.0000"}})
      (create-concept! ctx {:ontology-id ontology-id :uri "occupation:19" :label "Animal Scientist"
                            :scope :custom :broader ["Occupation"]
                            :attributes {:code "19-1011"}})
      (create-relationship! ctx "program:01" "prepares_for" "occupation:19")
      (let [model-spec {:entity-types [{:type "ProgramOfStudy" :uri-keying-fields ["CIP_Code"]}
                                       {:type "Occupation" :uri-keying-fields ["SOC_Code"]}]
                        :edges [{:predicate "prepares_for"}]}
            ;; the SHAPE EB3 actually authored in the live verify (kinds as strings,
            ;; types by name, functional over a keying field).
            candidates {:axioms
                        [{:kind ":disjoint" :types ["ProgramOfStudy" "Occupation"]
                          :rationale "programs and occupations are distinct"}
                         {:kind ":functional" :field "CIP_Code" :entity "ProgramOfStudy"
                          :rationale "CIP code uniquely identifies a program"}]}
            report (eb6/emit-axioms! ctx {:ontology-id ontology-id
                                          :candidate-axioms candidates
                                          :model-spec model-spec})
            axioms (rm/get-axioms ctx ontology-id)]
        (is (= 2 (:axioms-emitted-count report))
            "both the type-disjointness and the keying-field functional EMIT")
        ;; disjointness over the entity-type CLASSES landed (symmetric)
        (is (= #{"Occupation"} (get-in axioms [:disjointness "ProgramOfStudy"]))
            "disjointness over the entity TYPES landed (grounded via :broader class layer)")
        ;; functional over the URI-keying FIELD landed (predicate = the field)
        (is (contains? (get-in axioms [:characteristics "CIP_Code"] #{}) :functional)
            "functional over the URI-keying field landed (grounded via model-spec)")))))

(deftest unsupported-kind-is-surfaced-not-silently-skipped-test
  (testing "HONEST GAP: a kind with no emission path (domain/range/closure) is
            SURFACED in :axioms-unsupported with its rationale — NEVER silently
            skipped (silent skipping is exactly the :axioms-skipped bug EB6 closes)"
    (h/with-test-context [ctx]
      (seed-graph! ctx)
      (let [report (eb6/emit-axioms! ctx {:ontology-id ontology-id
                                          :candidate-axioms candidate-axioms})
            unsupported (:axioms-unsupported report)
            domain-entry (first (filter #(= :domain (:family %)) unsupported))]
        (is (some? domain-entry) ":domain is surfaced as unsupported")
        (is (str/includes? (:reason domain-entry) "no emission path")
            "the unsupported entry carries a reason")
        (is (= "domain of hasOwner is Dog" (:rationale domain-entry))
            "the candidate's rationale is preserved on the surfaced gap")
        ;; the unsupported kind did NOT silently land anything spurious
        (is (= 5 (:candidates-considered report))
            "every candidate is accounted for (considered count = input count)")))))

(deftest ungrounded-refs-are-surfaced-not-asserted-test
  (testing "HONEST GAP: a SUPPORTED kind whose class refs do NOT resolve against
            the real graph is SURFACED in :axioms-ungrounded — NOT asserted (no
            axiom over URIs the graph does not hold; discipline #5)"
    (h/with-test-context [ctx]
      (seed-graph! ctx)
      (let [report (eb6/emit-axioms! ctx {:ontology-id ontology-id
                                          :candidate-axioms candidate-axioms})
            ungrounded (:axioms-ungrounded report)
            entry (first (filter #(= :disjointness (:family %)) ungrounded))
            axioms (rm/get-axioms ctx ontology-id)]
        (is (some? entry) "the made-up disjointness is surfaced as ungrounded")
        (is (= ["Unicorn" "Dragon"] (:unresolved entry))
            "the unresolved references are surfaced verbatim")
        ;; adversarial: the made-up classes did NOT land in the projection
        (is (nil? (get-in axioms [:disjointness "Unicorn"]))
            "no disjointness asserted over a URI the graph does not hold")))))

(deftest every-candidate-is-accounted-for-no-silent-drop-test
  (testing "the closing invariant: considered = emitted + ungrounded + unsupported
            + rejected — NO candidate vanishes (the silent-drop guard)"
    (h/with-test-context [ctx]
      (seed-graph! ctx)
      (let [r (eb6/emit-axioms! ctx {:ontology-id ontology-id
                                     :candidate-axioms candidate-axioms})
            accounted (+ (:axioms-emitted-count r)
                         (count (:axioms-ungrounded r))
                         (count (:axioms-unsupported r))
                         (count (:axioms-rejected r)))]
        (is (= (:candidates-considered r) accounted)
            "every considered candidate lands in exactly one bucket — no silent drop")))))

(deftest empty-candidates-is-a-clean-noop-test
  (testing "no candidates → a clean empty report (no crash, nothing asserted)"
    (h/with-test-context [ctx]
      (seed-graph! ctx)
      (let [r (eb6/emit-axioms! ctx {:ontology-id ontology-id :candidate-axioms {:axioms []}})]
        (is (= 0 (:candidates-considered r)))
        (is (= 0 (:axioms-emitted-count r)))
        (is (empty? (:axioms-unsupported r)))))))

;; =============================================================================
;; The EB6 MINT — assert-sub-class lands a real axiom + exports as rdfs:subClassOf.
;; =============================================================================

(deftest minted-sub-class-command-round-trips-through-projection-test
  (testing "the EB6-minted :ontology/assert-sub-class command emits an event that
            projects to :sub-class-of (mirrors assert-sub-property exactly)"
    (h/with-test-context [ctx]
      (h/run-and-apply! ctx (fn [c] (cmd/ontology-assert-sub-class
                                     (assoc c :command {:ontology-id ontology-id
                                                        :sub-class "ex:Dog"
                                                        :super-class "ex:Animal"}))))
      (let [axioms (rmp/project ctx :ontology/axioms)]
        (is (= "ex:Animal" (get-in axioms [ontology-id :sub-class-of "ex:Dog"]))
            "Dog subClassOf Animal in the axiom projection")))))

(deftest minted-sub-class-exports-as-rdfs-sub-class-of-test
  (testing "a sub-class assertion exports as rdfs:subClassOf in TTL (the EB6 mint
            completes the round-trip: command → event → projection → OWL export)"
    (h/with-test-context [ctx]
      (h/run-and-apply! ctx (fn [c] (cmd/ontology-assert-sub-class
                                     (assoc c :command {:ontology-id ontology-id
                                                        :sub-class "ex:Dog"
                                                        :super-class "ex:Animal"}))))
      (let [ttl (serialization/full-export ctx {:include-profiles? false
                                                :include-experiences? false})]
        (is (re-find #"ex:Dog[^.]*rdfs:subClassOf[^.]*ex:Animal" ttl)
            "rdfs:subClassOf triple present in TTL export")))))

;; =============================================================================
;; Adversarial: a command-rejected body surfaces LOUDLY (not swallowed).
;; =============================================================================

(deftest command-rejection-surfaces-as-rejected-not-swallowed-test
  (testing "if a grounded body is rejected by the S07 Malli gate, EB6 surfaces it
            in :axioms-rejected with the anomaly — a LOUD surface, not a swallowed
            skip. (We force this by grounding a disjointness whose two refs
            collapse to the SAME URI — <2 distinct classes after grounding is
            caught BEFORE emission as :ungrounded, which is itself a surface; the
            command path's loud-surface is exercised directly here.)"
    (h/with-test-context [ctx]
      (let [;; a singleton disjointness sent straight at the command proves the
            ;; gate rejects + EB6's emit loop would capture it as :rejected.
            result (cp/process-command
                    (assoc ctx :command
                           {:command/name :ontology/assert-disjointness
                            :command/id (random-uuid)
                            :command/timestamp (time/now)
                            :ontology-id ontology-id
                            :class-uris ["entity:dog"]}))]
        (is (some? (::anom/category result))
            "the S07 gate rejects a singleton disjointness with an anomaly")))))

;; =============================================================================
;; Domain-agnostic (#12) — the EB6 code carries no vertical vocabulary.
;; =============================================================================

(deftest kind-family-table-is-domain-agnostic-test
  (testing "the kind→family table is OWL/RDF vocabulary only — no vertical terms"
    (let [terms (set (keys eb6/kind->family))]
      (doseq [leak ["cip" "soc" "ipeds" "opeid" "occupation" "institution"
                    "degree" "crosswalk"]]
        (is (not (contains? terms leak))
            (str "the kind→family table must not bake in the vertical term: " leak))))))
