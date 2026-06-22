(ns ai.obney.orc.ontology.core.axiom-tbox-subbehavior
  "EB6 — the AXIOM/TBox subbehavior as a delegatable ORC sheet.

   The FIFTH real subbehavior on the EB1 registry/delegation pattern (after EB2
   Survey + EB3 Model + EB4 Extract + EB5 Reconcile). A subbehavior is a
   first-class composed ORC sheet, built via the DSL + `build-workflow!`,
   registered under a stable name → deterministic sheet-id, and invoked from a
   central evolver tree via `:delegate` (child tick, isolated blackboard, mapped
   `:reads`/`:writes`).

   ## What Axiom/TBox does (its ONE job)

   Turn EB3's CANDIDATE-AXIOMS (`{:axioms [{:kind … :rationale …} …]}`) + the
   EXTRACTED GRAPH (read via `:ontology-id`) into REAL TBox axioms emitted through
   the S07 axiom commands. It CLOSES the still-open `:axioms-skipped` silent drop:
   EB3 produced candidate axioms, but the discovery/skeleton path that did not run
   the V07 axiom-draft route simply skipped them. EB6 is the delegatable step that
   maps each candidate by `:kind` → the matching S07 command, GROUNDS its class /
   predicate references against the REAL extracted graph (it never asserts over a
   URI the graph does not hold), emits, and READS THE AXIOMS BACK from the
   projection (discipline 7 — events LAND; we do not trust a return value).

   ## A single `:code` node — Axiom/TBox is DETERMINISTIC coercion

   Mapping a candidate `:kind` onto an S07 command + grounding its references
   against the real graph is a DETERMINISTIC coercion (the SAME shape as the V07
   `axiom-draft->command` precedent, REUSING its vocabulary-normalization tables —
   no fork, discipline 8). There is no reasoning to do here: EB3 already DID the
   reasoning (each candidate carries its `:rationale`), so the body is ONE `:code`
   node, NO `:llm` node. (When a future kind genuinely needs derivation — e.g. a
   closure axiom from 'the source enumerates a set completely' — that `:llm` step
   would write `:reasoning` FIRST (#13); EB6 does not need it for the four S07
   families + the minted sub-class.)

   ## The honest-gap rule (load-bearing — EB6 CLOSES a silent-drop bug)

   EB6 must NOT reintroduce a silent drop. Every candidate is accounted for:
     - EMITTED   — mapped to an S07 command, grounded, landed (read back).
     - UNSUPPORTED — a `:kind` with NO emission path today (domain / range /
       closure). SURFACED in `:axioms-unsupported` with the candidate + rationale,
       never silently skipped. These are TRACKED GAPS (no S07 command + no
       single-isomorphic mint; they reference class×predicate pairs with distinct
       semantics that a future slice owns).
     - UNGROUNDED — the `:kind` IS supported but its class/predicate references do
       not resolve against the REAL graph (would assert over URIs the graph does
       not hold). SURFACED in `:axioms-ungrounded` with the unresolved references —
       not asserted (no fabricated axiom; discipline #5), not silently dropped.
     - REJECTED   — the S07 command itself rejected the grounded body (e.g. <2
       distinct disjoint classes after grounding). SURFACED in `:axioms-rejected`
       with the anomaly — a LOUD surface, not a swallowed skip.

   ## The EB6 MINT — `:ontology/assert-sub-class`

   EB3 actively proposes `:sub-class` candidates, but S07 had NO subClassOf command
   (class subsumption could only be set on the concept-creation `:broader` SKOS
   field, which CANNOT be asserted over ALREADY-LANDED concepts at axiom time). So a
   `:sub-class` candidate had no emission target and was the exact silent drop EB6
   closes. EB6 MINTS `:ontology/assert-sub-class` — isomorphic to the proven
   `assert-sub-property` (schema + defcommand + projection `:sub-class-of` +
   `rdfs:subClassOf` TTL export) — so subClassOf LANDS as a real TBox axiom. The
   larger domain/range/closure families remain tracked gaps (distinct semantics,
   not a single-isomorphic mint, and EB3 does not currently emit them).

   ## Grounding — deterministic, evidence-based, NO hardcoded phrase matching

   A candidate references CLASSES by URI or label and PREDICATES by name. EB6
   grounds them against the REAL graph: a class reference resolves if it is a
   concept URI present in the graph OR matches a concept `:label` EXACTLY; a
   predicate reference resolves if it is a `:predicate` on a real relationship in
   the graph. This is exact, structural resolution against the runtime model — NOT
   a fuzzy phrase list (#7/#12) and NOT vertical vocabulary (#12). Anything that
   does not resolve is surfaced as `:axioms-ungrounded`, never asserted.

   ## C1 — what crosses `:delegate`

   The OUTPUT is the emission REPORT (`:axiom-report`). It is produced by a `:code`
   node → it crosses `:delegate` PARSED (the C1 `:llm` JSON-string failure mode is
   node-type-specific to the AI executor's schema-coercion path, which a `:code`
   write does not traverse). The blackboard still declares a STRUCTURED schema for
   it (the EB2/EB3/EB4/EB5 defense-in-depth)."
  (:require [ai.obney.orc.orc-service.interface :as dsl]
            [ai.obney.orc.ontology.core.read-models :as rm]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.time.interface :as time]
            [clojure.string :as str]
            [cognitect.anomalies :as anom]))

;; =============================================================================
;; The axiom-report contract — the public OUTPUT
;; =============================================================================

(def axiom-report-key
  "The Axiom/TBox subbehavior's public OUTPUT contract: a single emission REPORT
   map. It accounts for EVERY candidate (closing the silent-drop bug):
   `:axioms-emitted` (mapped → grounded → LANDED, read back), `:axioms-ungrounded`
   (supported kind whose references don't resolve against the real graph),
   `:axioms-unsupported` (a kind with no emission path — a tracked gap), and
   `:axioms-rejected` (the S07 command rejected the grounded body). Produced by a
   `:code` node → crosses `:delegate` PARSED."
  :axiom-report)

(def axiom-report-schema
  "STRUCTURED Malli schema for the `:axiom-report` write. A concrete `[:map …]`
   (NOT a bare `:map`/`:any`) documents the report shape and keeps the contract
   robust across `:delegate` (the EB2-EB5 defense-in-depth). `{:closed false}` +
   `:any` leaf values tolerate the rich per-entry shapes."
  [:map {:closed false}
   [:ontology-id {:optional true} :any]
   [:candidates-considered {:optional true} :any]
   [:axioms-emitted {:optional true} :any]
   [:axioms-emitted-count {:optional true} :any]
   [:axioms-ungrounded {:optional true} :any]
   [:axioms-unsupported {:optional true} :any]
   [:axioms-rejected {:optional true} :any]
   ;; the axioms READ BACK from the projection (discipline 7 — proof they LANDED)
   [:axioms-read-back {:optional true} :any]])

;; =============================================================================
;; Kind normalization — REUSE the V07 vocabulary tables (no fork, discipline 8)
;; =============================================================================
;;
;; EB3's candidate `:kind` and V07's draft `:axiom-type` are the SAME
;; discriminator with the same OWL/RDF-standard synonyms. EB6 reuses the proven
;; normalization (strip owl:/rdfs: prefix + separators, lower-case) and the
;; family alias table, EXTENDED with the EB6-minted :sub-class family + the
;; tracked-gap families (domain / range / closure) so an unsupported kind is
;; RECOGNIZED and surfaced (not mistaken for an unknown typo).

(defn normalize-vocab-term
  "Lower-case a vocabulary term and strip any `owl:`/`rdfs:`/`rdf:` namespace
   prefix plus `-`/`_`/`:`/space separators so OWL-standard names compare
   uniformly. The SAME normalization the V07 axiom-draft route uses."
  [x]
  (when (some? x)
    (-> (name x)
        (str/replace #"^(?i)(owl|rdfs|rdf):" "")
        (str/replace #"[-_: ]" "")
        str/lower-case)))

(def kind->family
  "Map a normalized candidate `:kind` term → an axiom FAMILY. The four S07
   families (REUSED from the V07 alias table) PLUS the EB6-minted :sub-class and
   the tracked-gap families (:domain / :range / :closure) so EB6 RECOGNIZES an
   unsupported kind and surfaces it deliberately (rather than as an unknown typo).
   Not fuzzy phrase matching: each key is an exact normalized OWL/RDF term."
  {;; (1) Disjointness → assert-disjointness
   "disjoint"               :disjointness
   "disjointness"           :disjointness
   "disjointwith"           :disjointness
   "disjointclasses"        :disjointness
   "alldisjointclasses"     :disjointness
   ;; (2) Property characteristic → assert-property-characteristic
   "functional"             :property-characteristic
   "functionalproperty"     :property-characteristic
   "transitive"             :property-characteristic
   "transitiveproperty"     :property-characteristic
   "symmetric"              :property-characteristic
   "symmetricproperty"      :property-characteristic
   "propertycharacteristic" :property-characteristic
   "characteristic"         :property-characteristic
   "inverseof"              :property-characteristic
   "inverseproperty"        :property-characteristic
   ;; (3) Sub-property → assert-sub-property
   "subproperty"            :sub-property
   "subpropertyof"          :sub-property
   ;; (3b) Sub-class → assert-sub-class (EB6 MINT)
   "subclass"               :sub-class
   "subclassof"             :sub-class
   "isa"                    :sub-class
   "specializationof"       :sub-class
   ;; (4) Chain → assert-chain-axiom
   "chain"                  :chain
   "chainaxiom"             :chain
   "propertychain"          :chain
   "propertychainaxiom"     :chain
   ;; tracked gaps — RECOGNIZED but with NO emission path today.
   "domain"                 :domain
   "rdfsdomain"             :domain
   "range"                  :range
   "rdfsrange"              :range
   "closure"                :closure
   "closed"                 :closure
   "completeenumeration"    :closure})

(def supported-families
  "Families with an S07 (or EB6-minted) emission path."
  #{:disjointness :property-characteristic :sub-property :sub-class :chain})

(def characteristic-aliases
  "Normalized term → S07 property-characteristic enum flag. REUSED from V07."
  {"functional"         :functional
   "functionalproperty" :functional
   "transitive"         :transitive
   "transitiveproperty" :transitive
   "symmetric"          :symmetric
   "symmetricproperty"  :symmetric})

(defn characteristic-flag-from-kind
  "When the candidate encodes the characteristic IN the `:kind` term
   (`:functional` / `:transitive` / `:symmetric`), recover the S07 enum flag.
   Returns nil for inverse-of / a bare `:property-characteristic`."
  [kind]
  (get characteristic-aliases (normalize-vocab-term kind)))

;; =============================================================================
;; Reference extraction — pull the class / predicate references out of a tolerant
;; candidate map (the EB3 candidate shape is `{:kind … :rationale … + fields}`)
;; =============================================================================

(defn- as-vec
  "Coerce a candidate field value to a vector of scalar references. A candidate
   may carry a single string or a vector; nil → empty."
  [v]
  (cond
    (nil? v) []
    (sequential? v) (vec v)
    :else [v]))

(defn class-references
  "Extract the CLASS references a candidate concerns, tolerant of the field
   names EB3/the model reaches for (`:classes` / `:class-uris` / `:types` /
   `:entity-types` / `:class` / `:type`). Domain-agnostic — names no domain
   field; it reads whatever class-ish fields the candidate carries."
  [candidate]
  (vec (distinct (mapcat #(as-vec (get candidate %))
                         [:class-uris :classes :types :entity-types :class :type]))))

(defn sub-super-class-references
  "Extract the (sub, super) CLASS reference pair for a :sub-class candidate,
   tolerant of field names (`:sub-class`/`:sub` and `:super-class`/`:super`).
   Falls back to the first two `class-references` when the model used a generic
   pair field."
  [candidate]
  (let [sub (or (:sub-class candidate) (:sub candidate) (:subclass candidate)
                (:specific candidate) (:child candidate))
        super (or (:super-class candidate) (:super candidate) (:superclass candidate)
                  (:general candidate) (:parent candidate))
        refs (class-references candidate)]
    [(or sub (first refs)) (or super (second refs))]))

(defn predicate-reference
  "Extract the single PREDICATE reference a property candidate concerns,
   tolerant of field names. Includes `:field` — a `:functional` candidate over an
   identifying/URI-keying FIELD names the property by its field name (the field IS
   the datatype property)."
  [candidate]
  (or (:predicate candidate) (:property candidate) (:edge candidate)
      (:field candidate) (:key candidate)))

(defn sub-super-predicate-references
  "Extract the (sub, super) PREDICATE pair for a :sub-property candidate."
  [candidate]
  [(or (:sub-predicate candidate) (:sub-property candidate) (:sub candidate))
   (or (:super-predicate candidate) (:super-property candidate) (:super candidate))])

;; =============================================================================
;; Grounding — resolve a reference against the REAL extracted graph
;; =============================================================================

(defn- name* [x]
  (cond (keyword? x) (name x) (symbol? x) (name x) (nil? x) nil :else (str x)))

(defn graph-grounding
  "Build the grounding index from the REAL extracted graph for `:ontology-id`
   (+ the OPTIONAL model-spec — the runtime modeling decision the central tree
   already holds from EB3). The index reflects how the graph ACTUALLY represents
   classes + properties so a candidate's references resolve against real structure
   (NOT vertical vocab, NOT fuzzy phrase matching — #7/#12):

   CLASS layer (a class reference resolves against any of):
     :concept-uris  — concept URIs present (an instance referenced directly)
     :label->uri    — EXACT concept label → its URI
     :classes       — the `:broader` SKOS classes the instances carry (this is
                      how EB4 represents the ENTITY TYPE — each instance is
                      `:broader` of its type name; the type IS the class layer)
     + model-spec :entity-types' :type names (the declared classes)
   The class layer resolves a reference to its CANONICAL class form (the
   `:broader`/type string itself when it is a class, else the instance URI).

   PROPERTY layer (a predicate reference resolves against any of):
     :predicates    — predicates on real relationships
     :attr-keys     — concept attribute keys (datatype properties on instances)
     + model-spec edges' :predicate and entity-types' :uri-keying-fields (a
       URI-keying field is a real identifying/functional datatype property even
       though instance extraction does not materialize it as an edge)."
  ([ctx ontology-id] (graph-grounding ctx ontology-id nil))
  ([ctx ontology-id model-spec]
   (let [concepts (rm/get-concepts ctx {:ontology-id ontology-id})
         relationships (rm/get-relationships ctx)
         concept-uri-set (set (map :uri concepts))
         in-onto-rels (filter (fn [r]
                                (or (contains? concept-uri-set (:source-uri r))
                                    (contains? concept-uri-set (:target-uri r))))
                              relationships)
         ;; the real class layer = the :broader SKOS classes instances carry.
         broader-classes (set (mapcat #(map name* (or (:broader %) [])) concepts))
         ;; the declared entity-type classes from the model-spec (if supplied).
         spec-classes (set (keep #(name* (:type %)) (:entity-types model-spec)))
         ;; the real datatype-property layer = concept attribute keys.
         attr-keys (set (mapcat #(map name* (keys (or (:attributes %) {}))) concepts))
         ;; model-spec edge predicates + URI-keying fields (real properties even
         ;; when instance extraction did not materialize them as edges).
         spec-edge-preds (set (keep #(name* (:predicate %)) (:edges model-spec)))
         spec-key-fields (set (mapcat #(map name* (or (:uri-keying-fields %) []))
                                      (:entity-types model-spec)))]
     {:concept-uris concept-uri-set
      :label->uri (reduce (fn [acc c]
                            (if (and (:label c) (not (contains? acc (:label c))))
                              (assoc acc (:label c) (:uri c))
                              acc))
                          {} concepts)
      :classes (into broader-classes spec-classes)
      :predicates (set (keep :predicate in-onto-rels))
      :attr-keys attr-keys
      :spec-edge-preds spec-edge-preds
      :spec-key-fields spec-key-fields})))

(defn ground-class-ref
  "Resolve ONE class reference to its canonical real-graph class form, or nil if
   it does not resolve. Resolves if it is: a `:broader`/declared CLASS (returned
   as the class string — the entity TYPE), a concept URI, or an EXACT concept
   label (returned as the instance URI). Deterministic, evidence-based (#7)."
  [grounding ref]
  (when-let [s (name* ref)]
    (cond
      (contains? (:classes grounding) s) s
      (contains? (:concept-uris grounding) s) s
      (contains? (:label->uri grounding) s) (get (:label->uri grounding) s)
      :else nil)))

(defn ground-predicate-ref
  "Resolve ONE predicate reference against the real PROPERTY layer. A predicate
   resolves if it is a `:predicate` on a real relationship, a concept ATTRIBUTE
   KEY (datatype property), a model-spec EDGE predicate, or a model-spec
   URI-KEYING FIELD (a real identifying/functional datatype property). Returns
   the predicate string or nil. Deterministic, evidence-based (#7)."
  [grounding ref]
  (when-let [s (name* ref)]
    (when (or (contains? (:predicates grounding) s)
              (contains? (:attr-keys grounding) s)
              (contains? (:spec-edge-preds grounding) s)
              (contains? (:spec-key-fields grounding) s))
      s)))

;; =============================================================================
;; Candidate → grounded S07 command (or a surfaced gap entry)
;; =============================================================================

(defn- base-command [ontology-id]
  {:command/id (random-uuid)
   :command/timestamp (time/now)
   :ontology-id ontology-id})

(defn candidate->plan
  "Map ONE candidate to a PLAN: either a grounded S07 command, or a surfaced
   reason (`:unsupported` / `:ungrounded`). NEVER fabricates an axiom and NEVER
   silently drops — every candidate yields a plan entry. Returns:
     {:status :command  :family <kw> :command <map> :grounded <map>}
     {:status :unsupported :family <kw> :kind <orig> :reason <str>}
     {:status :ungrounded  :family <kw> :kind <orig> :reason <str> :unresolved <vec>}"
  [ontology-id grounding {:keys [kind rationale] :as candidate}]
  (let [family (get kind->family (normalize-vocab-term kind))
        base (base-command ontology-id)
        surface (fn [status m]
                  (merge {:status status :family family :kind kind :rationale rationale} m))]
    (cond
      ;; A kind we do not recognize at all → still surfaced (no silent drop).
      (nil? family)
      (surface :unsupported {:reason (str "unrecognized axiom kind " (pr-str kind)
                                          " — no S07 family")})

      ;; A recognized-but-pathless family (domain / range / closure) → tracked gap.
      (not (contains? supported-families family))
      (surface :unsupported {:reason (str "no emission path for " (name family)
                                          " today (tracked gap — distinct semantics, "
                                          "no single-isomorphic S07 command)")})

      :else
      (case family
        :disjointness
        (let [refs (class-references candidate)
              grounded (vec (distinct (keep #(ground-class-ref grounding %) refs)))
              unresolved (vec (remove #(ground-class-ref grounding %) refs))]
          (if (>= (count grounded) 2)
            {:status :command :family family
             :grounded {:class-uris grounded}
             :command (assoc base
                             :command/name :ontology/assert-disjointness
                             :class-uris grounded)}
            (surface :ungrounded
                     {:reason (str "disjointness needs >=2 class references that "
                                   "resolve in the graph; resolved "
                                   (count grounded) " of " (count refs))
                      :unresolved unresolved})))

        :property-characteristic
        (let [pred-ref (predicate-reference candidate)
              pred (ground-predicate-ref grounding pred-ref)
              ;; the flag may be in the :kind term OR an explicit field
              field-flags (keep #(get characteristic-aliases (normalize-vocab-term %))
                                (as-vec (or (:characteristic candidate)
                                            (:characteristics candidate))))
              term-flag (characteristic-flag-from-kind kind)
              flags (vec (distinct (cond-> (vec field-flags)
                                     (and term-flag (not (some #{term-flag} field-flags)))
                                     (conj term-flag))))
              inverse-ref (or (:inverse-of candidate) (:inverse candidate))
              inverse (ground-predicate-ref grounding inverse-ref)]
          (cond
            (nil? pred)
            (surface :ungrounded
                     {:reason (str "property predicate " (pr-str pred-ref)
                                   " does not resolve to a real relationship predicate")
                      :unresolved [pred-ref]})
            (and (empty? flags) (nil? inverse))
            (surface :ungrounded
                     {:reason "no valid characteristic flag and no resolved inverse-of"
                      :unresolved [(:characteristic candidate) inverse-ref]})
            :else
            {:status :command :family family
             :grounded (cond-> {:predicate pred :characteristic flags}
                         inverse (assoc :inverse-of inverse))
             :command (cond-> (assoc base
                                     :command/name :ontology/assert-property-characteristic
                                     :predicate pred
                                     :characteristic flags)
                        inverse (assoc :inverse-of inverse))}))

        :sub-property
        (let [[sub-ref super-ref] (sub-super-predicate-references candidate)
              sub (ground-predicate-ref grounding sub-ref)
              super (ground-predicate-ref grounding super-ref)]
          (if (and sub super)
            {:status :command :family family
             :grounded {:sub-predicate sub :super-predicate super}
             :command (assoc base
                             :command/name :ontology/assert-sub-property
                             :sub-predicate sub :super-predicate super)}
            (surface :ungrounded
                     {:reason "sub-property needs both predicates to resolve in the graph"
                      :unresolved (vec (remove (set [sub super]) [sub-ref super-ref]))})))

        :sub-class
        (let [[sub-ref super-ref] (sub-super-class-references candidate)
              sub (ground-class-ref grounding sub-ref)
              super (ground-class-ref grounding super-ref)]
          (if (and sub super (not= sub super))
            {:status :command :family family
             :grounded {:sub-class sub :super-class super}
             :command (assoc base
                             :command/name :ontology/assert-sub-class
                             :sub-class sub :super-class super)}
            (surface :ungrounded
                     {:reason "sub-class needs two DISTINCT class references that resolve in the graph"
                      :unresolved (vec (remove (set (keep identity [sub super]))
                                               [sub-ref super-ref]))})))

        :chain
        (let [chain-refs (as-vec (or (:chain candidate) (:properties candidate)))
              chain (vec (keep #(ground-predicate-ref grounding %) chain-refs))
              derived-ref (or (:derived-predicate candidate) (:derived candidate)
                              (:predicate candidate))
              derived (or (ground-predicate-ref grounding derived-ref)
                          ;; a chain's DERIVED predicate is a NEW predicate the
                          ;; chain defines — it need not already exist in the
                          ;; graph; accept the literal when given.
                          (when (some? derived-ref) (str derived-ref)))]
          (if (and (>= (count chain) 2) derived)
            {:status :command :family family
             :grounded {:chain chain :derived-predicate derived}
             :command (assoc base
                             :command/name :ontology/assert-chain-axiom
                             :chain chain :derived-predicate derived)}
            (surface :ungrounded
                     {:reason (str "chain needs >=2 chain predicates that resolve + a derived "
                                   "predicate; resolved " (count chain) " chain predicates")
                      :unresolved (vec (remove (set chain) chain-refs))})))))))

;; =============================================================================
;; The orchestrating emit — plan, emit grounded commands, READ BACK (discipline 7)
;; =============================================================================

(defn emit-axioms!
  "Orchestrate the full Axiom/TBox emission. Given the granted `:ontology-id` +
   EB3's candidate-axioms (the `{:axioms [...]}` wrapper OR a bare vector):

     1. Read the REAL graph grounding (concept URIs + labels + predicates).
     2. For EACH candidate, build a PLAN (grounded command / ungrounded /
        unsupported) — every candidate is accounted for (no silent drop).
     3. Emit each grounded command via `cp/process-command`. A command-level
        rejection (e.g. the Malli gate) is captured as `:axioms-rejected` — a
        LOUD surface, not a swallowed skip.
     4. READ THE AXIOMS BACK from the projection (discipline 7) — proof they
        LANDED; this is the report's `:axioms-read-back`, not a return value.

   Returns the public axiom report."
  [ctx {:keys [ontology-id candidate-axioms model-spec]}]
  (when-not ontology-id
    (throw (ex-info "emit-axioms! requires :ontology-id (the granted scope)"
                    {:ontology-id ontology-id})))
  (let [;; tolerate the `{:axioms [...]}` wrapper OR a bare vector of candidates.
        candidates (vec (cond
                          (map? candidate-axioms) (or (:axioms candidate-axioms) [])
                          (sequential? candidate-axioms) candidate-axioms
                          :else []))
        ;; the OPTIONAL model-spec (the runtime modeling decision the central tree
        ;; holds from EB3) deepens grounding: entity-type classes + URI-keying
        ;; fields become groundable. EB6 still works graph-only when it is nil.
        grounding (graph-grounding ctx ontology-id model-spec)
        plans (mapv #(candidate->plan ontology-id grounding %) candidates)
        {:keys [command unsupported ungrounded]}
        (group-by :status plans)
        ;; emit each grounded command; collect emitted vs rejected.
        emit-results
        (mapv (fn [{:keys [family grounded command]}]
                (let [result (cp/process-command (assoc ctx :command command))]
                  (if (::anom/category result)
                    {:status :rejected :family family :grounded grounded :anomaly result}
                    {:status :emitted :family family :grounded grounded})))
              command)
        emitted (filterv #(= :emitted (:status %)) emit-results)
        rejected (filterv #(= :rejected (:status %)) emit-results)
        ;; DISCIPLINE 7 — read the axioms BACK from the projection (proof they LANDED).
        read-back (rm/get-axioms ctx ontology-id)]
    {:ontology-id ontology-id
     :candidates-considered (count candidates)
     :axioms-emitted (mapv #(select-keys % [:family :grounded]) emitted)
     :axioms-emitted-count (count emitted)
     :axioms-ungrounded (mapv #(select-keys % [:family :kind :reason :unresolved :rationale])
                              (or ungrounded []))
     :axioms-unsupported (mapv #(select-keys % [:family :kind :reason :rationale])
                               (or unsupported []))
     :axioms-rejected (mapv #(select-keys % [:family :grounded :anomaly]) rejected)
     :axioms-read-back read-back}))

;; =============================================================================
;; The `:code` node wrapper + the delegatable sheet
;; =============================================================================

(defn emit-axioms-code
  "The `:code` `:fn`. The orc-service `:code` executor calls the `:fn` with
   `(assoc context :inputs <reads-map> :execution-context context)` — so the ctx
   (event-store, cache, registries, …) IS the top-level arg map, and the node's
   `:reads` arrive under `:inputs`. Runs the full `emit-axioms!` orchestration
   against that ctx and writes the public `:axiom-report` (native Clojure —
   crosses `:delegate` parsed)."
  [{:keys [inputs] :as ctx}]
  (let [{:keys [ontology-id candidate-axioms model-spec]} inputs]
    {axiom-report-key
     (emit-axioms!
      (dissoc ctx :inputs :execution-context)
      {:ontology-id ontology-id
       :candidate-axioms candidate-axioms
       :model-spec model-spec})}))

(defn axiom-tbox-subbehavior-name
  "Canonical registry name for the Axiom/TBox subbehavior. Like EB3-EB5 (and
   UNLIKE per-source Survey), it bakes in NO source path — it grounds the
   candidate axioms it is handed against the CURRENT graph for the `:ontology-id`
   it is handed (both `:reads` inputs), so a SINGLE Axiom/TBox sheet serves every
   source and graph. `\"<family>/<behavior>@v<N>\"` — version is part of identity
   (a new version is a new, separately-evolvable sheet)."
  []
  "ontology-axiom-tbox/axiom-tbox@v1")

(defn axiom-tbox-sheet-id-for
  "Look up the deterministic sheet-id for the Axiom/TBox subbehavior (pure — no
   event-store read). The central tree points its `:delegate` `:target-sheet-id`
   here without rebuilding the subbehavior."
  []
  (dsl/sheet-id-for-name (axiom-tbox-subbehavior-name)))

(defn axiom-tbox-subbehavior-def
  "The Axiom/TBox subbehavior workflow definition.

   Body: a single `:code` node — the deterministic candidate→S07 coercion +
   grounding + emission + read-back. NO `:llm` node (EB3 already did the
   reasoning; mapping a `:kind` to an S07 command is deterministic coercion).

   Contract (the public `:reads`/`:writes`):
     :reads  [:ontology-id :candidate-axioms :model-spec]
     :writes [:axiom-report]
   `:model-spec` is the EB3 modeling decision (entity-type classes + URI-keying
   fields) that DEEPENS grounding — the central tree already holds it from EB3.
   It is tolerated as `[:maybe …]` so EB6 still runs graph-only when omitted.
   The report is a `:code`-node output → it crosses `:delegate` PARSED; the
   blackboard declares a STRUCTURED schema for it (defense-in-depth)."
  [{:keys [_model]}]
  (let [nm (axiom-tbox-subbehavior-name)]
    (dsl/workflow nm
      (dsl/blackboard {;; public :reads — the granted scope + EB3's candidate axioms
                       :ontology-id :any
                       ;; tolerate the `{:axioms [...]}` wrapper (the EB3 write
                       ;; shape) — closed false so any candidate fields pass.
                       :candidate-axioms [:map {:closed false}]
                       ;; the EB3 model-spec (optional) — deepens grounding.
                       :model-spec [:maybe [:map {:closed false}]]
                       ;; public :write — the emission report
                       axiom-report-key axiom-report-schema})
      (dsl/sequence "axiom-tbox-root"
        (dsl/code "emit-axioms"
          :fn "ai.obney.orc.ontology.core.axiom-tbox-subbehavior/emit-axioms-code"
          :reads [:ontology-id :candidate-axioms :model-spec]
          :writes [axiom-report-key])))))

(defn register-axiom-tbox-subbehavior!
  "REGISTER (build, idempotent) the Axiom/TBox subbehavior sheet and return its
   deterministic sheet-id. Re-registering an unchanged def is a no-op (same id).
   The central evolver tree resolves the name → id via `axiom-tbox-sheet-id-for`
   and `:delegate`s to it."
  [ctx {:keys [model]}]
  (dsl/build-workflow! ctx (axiom-tbox-subbehavior-def {:_model model})))
