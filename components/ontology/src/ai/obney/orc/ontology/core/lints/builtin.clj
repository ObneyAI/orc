(ns ai.obney.orc.ontology.core.lints.builtin
  "S10 + S11 — Built-in lint shapes for the M4 lint set.

   --- S10 shipped (3) ---
   1. Dangling endpoint   — expressible via :property + :not
   2. Naming convention   — :code escape hatch
   3. Language-tag misuse — :code escape hatch

   --- S11 shipped (8) ---
    4. Disjointness violation        — axiom-consuming (:code), :violation
    5. Missing-disjointness warning  — :code, :warning
    6. Universal-without-existential — :code, :warning
    7. Closure-axiom absence         — :code, :info
    8. Roles-vs-classes              — :code, :warning (heuristic; suffix list)
    9. Name-implied semantics        — :code, :info (heuristic; <Adjective><Noun>)
   10. Functional-property double values — :code, :violation
                                          (CRITICAL: this is a LINT, NOT a
                                          silent sameAs/merge — projection
                                          state is unchanged)
   11. Single-parent discipline     — :code, :warning

   Each shape's `:code` form is a top-level defn here so it's reachable
   via `requiring-resolve` from the persisted :code-symbol form. All
   shapes ship in BOTH the in-process literal-fn form and the persistable
   symbol form (use the latter when registering through an event-store
   round-trip).

   Each lint is traceable to a course-verified failure mode (M4 lint
   set, PRD §M4):
   - Dangling endpoint     → relationship integrity
   - Naming convention     → URI shape consistency
   - Language tags         → B15 trap-lint
   - Disjointness          → classification correctness
   - Missing disjointness  → preventive flag
   - Universal-only        → vacuous-restriction trap
   - Closure absence       → 'complete enumeration without closing axiom'
   - Roles vs classes      → membership-changes-over-time modeling trap
   - Name-implied          → adjective-noun without backing assertion
   - Functional doubles    → OWA leak (sameAs inference DISALLOWED here)
   - Single parent         → ontology normalisation"
  (:require [clojure.string :as str]
            [clojure.set]))

;; =============================================================================
;; S10 lints — naming-convention + language-tag-misuse + dangling-endpoint
;; =============================================================================

(defn naming-convention-pred
  "Concept URIs must look like `prefix:section:Name` where prefix +
   section are lower-kebab and Name is Capital-start camel-or-kebab.

   Returns `nil` for clean, `{:violation? true :detail \"...\"}` for
   violation. Targets only concepts whose scope is :custom."
  [{:keys [concept]}]
  (when-not (re-matches #"^[a-z][a-z\-]*:[a-z][a-z\-]*:[A-Z][A-Za-z0-9\-]*$"
                        (str (:uri concept)))
    {:violation? true
     :detail (str "URI " (:uri concept) " fails pattern "
                  "concept-prefix:section:Name")}))

(defn language-tag-misuse-pred
  "A label carrying a non-empty `:lang` whose `:value` is non-linguistic
   (numbers / punctuation only) is the B15 trap."
  [{:keys [concept]}]
  (let [bad (->> (:labels concept)
                 (filter (fn [{:keys [value lang]}]
                           (and (seq lang)
                                (string? value)
                                (seq (str/trim value))
                                (re-matches #"^[\d\s\-\.,]+$" value))))
                 vec)]
    (when (seq bad)
      {:violation? true
       :detail (str "Non-linguistic label(s) carrying lang tag: "
                    (pr-str (mapv #(select-keys % [:value :lang]) bad)))})))

;; =============================================================================
;; S11 — Disjointness violation (axiom-consuming)
;; =============================================================================
;; The lint reads the axiom projection's :disjointness submap and the
;; concept's :broader set. If the concept asserts two broader-classes
;; that are recorded as mutually disjoint, fire.

(defn disjointness-violation-pred
  "Fire when a concept's :broader set contains two URIs that are
   asserted disjoint with each other (via S07 :ontology/disjointness-
   asserted events). Adversarial: with the axiom removed, the lint
   stays silent — verified by S11 test fixture."
  [{:keys [concept ctx]}]
  (let [broader (:broader concept)
        disjoint-map (get-in ctx [:axioms :disjointness])]
    (when (and disjoint-map (>= (count broader) 2))
      (let [conflicts
            (for [a broader
                  b broader
                  :when (and (not= a b)
                             (contains? (get disjoint-map a #{}) b))]
              [a b])]
        (when (seq conflicts)
          {:violation? true
           :detail (str "Concept " (:uri concept)
                        " typed under disjoint classes: "
                        (pr-str (vec (set (mapv set conflicts)))))})))))

;; =============================================================================
;; S11 — Missing-disjointness (axiom-consuming, sibling-group review)
;; =============================================================================
;; For each sibling-group (set of concepts sharing a common broader-class),
;; warn if no pair in the sibling-group is asserted disjoint. The lint is
;; emitted ONCE per concept that's part of an under-asserted sibling group.

(defn missing-disjointness-pred
  "Fire when this concept has at least one sibling under a shared parent
   AND NO pair in the sibling group has an asserted disjointness.

   The lint surfaces at the CONCEPT level (one violation per under-
   asserted member). Adversarial: with one disjointness asserted across
   the sibling group, the lint goes silent — even if some pairs in the
   group aren't asserted."
  [{:keys [concept graph ctx]}]
  (let [disjoint-map (get-in ctx [:axioms :disjointness] {})
        parents (:broader concept)]
    (when (seq parents)
      ;; Collect siblings: all concepts whose :broader contains any of
      ;; this concept's parents. Excludes self.
      (let [my-uri (:uri concept)
            siblings (->> (vals graph)
                          (filter (fn [c]
                                    (and (not= my-uri (:uri c))
                                         (seq (clojure.set/intersection
                                               (set (:broader c)) parents)))))
                          (mapv :uri))
            group (conj (set siblings) my-uri)]
        (when (seq siblings)
          ;; Does any pair in the group have a disjointness assertion?
          (let [any-asserted?
                (some (fn [a]
                        (some (fn [b]
                                (and (not= a b)
                                     (contains? (get disjoint-map a #{}) b)))
                              group))
                      group)]
            (when-not any-asserted?
              {:violation? true
               :detail (str "Sibling group under " (pr-str parents)
                            " has no disjointness assertion. Members: "
                            (pr-str (vec (sort group))))})))))))

;; =============================================================================
;; S11 — Universal-without-existential
;; =============================================================================
;; Heuristic: a concept whose ONLY relationship to a property is via the
;; "must NOT exist" :not-style negation, with NO positive assertion of
;; that same predicate. In our system, this surfaces when:
;;   - the concept's :related set is EMPTY (no positive edges)
;;   - AND the concept HAS some "negation marker" in :attributes
;;     (an :attributes/:negated-predicate or :attributes/:only)
;; The lint flags concepts whose ONLY pattern is "X has only Y values"
;; with no "X has some Y" — vacuously satisfiable.
;;
;; PRACTICAL impl: target concepts whose :attributes map declares a
;; `:universal-restriction` key (a list of predicates) AND whose
;; :typed-edges map has NO entries for any of those predicates.

(defn universal-without-existential-pred
  "Fire when a concept declares a universal restriction (via
   `:attributes/:universal-restriction` listing predicates) but the
   concept has NO positive (existential) edge for ANY of those
   predicates. Vacuously-satisfiable restriction pattern.

   The :attributes key shape is consumer-provided — extracted ontology
   tooling sets `:attributes {:universal-restriction [\"ex:hasMember\"]}`
   when a source asserts 'X has only Y' without a worked 'X has some Y'.
   Heuristic only — documented for consumer awareness."
  [{:keys [concept]}]
  (let [restrictions (get-in concept [:attributes :universal-restriction])
        typed-edges (or (:typed-edges concept) {})]
    (when (seq restrictions)
      (let [missing-existential
            (filter (fn [pred]
                      (empty? (get typed-edges pred)))
                    restrictions)]
        (when (seq missing-existential)
          {:violation? true
           :detail (str "Universal restriction(s) " (pr-str missing-existential)
                        " on " (:uri concept)
                        " have NO existential assertion (vacuously satisfiable)")})))))

;; =============================================================================
;; S11 — Closure-axiom absence
;; =============================================================================
;; Advisory: a concept that is part of a sibling group where ALL OTHER
;; siblings have at least one disjointness assertion BUT the parent
;; class has no `owl:oneOf`/enumeration marker. Signals that the
;; enumeration may be COMPLETE but not closed.
;;
;; PRACTICAL impl: target concept whose parent appears in the
;; :ontology/concepts as a class, where the sibling group has
;; >=N disjointness assertions on EVERY OTHER member AND the parent's
;; :attributes lack `:closed? true`.

(defn closure-axiom-absence-pred
  "Fire (advisory :info) when this concept's parent class is plausibly
   enumerable (every other sibling has at least one disjointness
   assertion) but the parent itself carries no `:attributes/:closed?
   true` marker (i.e., no owl:oneOf-equivalent closing assertion).

   Heuristic — informational, not a hard violation."
  [{:keys [concept graph ctx]}]
  (let [disjoint-map (get-in ctx [:axioms :disjointness] {})
        parents (:broader concept)]
    (when (seq parents)
      (let [my-uri (:uri concept)
            ;; Build per-parent: do ALL OTHER siblings have at least one
            ;; disjointness assertion?
            offenders
            (filter (fn [parent-uri]
                      (let [siblings (->> (vals graph)
                                          (filter (fn [c]
                                                    (and (not= my-uri (:uri c))
                                                         (contains? (:broader c) parent-uri))))
                                          (mapv :uri))
                            parent-concept (get graph parent-uri)
                            closed? (boolean (get-in parent-concept [:attributes :closed?]))]
                        (and (seq siblings)
                             (every? (fn [sib]
                                       (seq (get disjoint-map sib #{})))
                                     siblings)
                             (not closed?))))
                    parents)]
        (when (seq offenders)
          {:violation? true
           :detail (str "Parent class(es) " (pr-str offenders)
                        " appear completely enumerated (all other siblings carry "
                        "disjointness) but have no closing axiom (no :closed? marker).")})))))

;; =============================================================================
;; S11 — Roles-vs-classes (heuristic :code)
;; =============================================================================

(def role-shaped-class-suffixes
  "Surface-form list flagging concepts named with a role-shaped suffix.
   This is the LINT-side heuristic data; the lint reads its own
   declarative rule list (NOT hardcoded in production logic gates —
   declared here as data, consulted by the predicate fn). Membership
   tuning is consumer-overridable by registering a `:code-symbol`
   variant that consults a custom suffix list.

   Each entry is a lowercase suffix tested against the concept's URI
   local-name (the segment after the last `:`)."
  ["employee" "customer" "user" "client" "manager" "applicant"
   "patient" "student" "teacher" "owner" "supplier" "contractor"
   "visitor" "tenant" "buyer" "seller" "member"])

(defn- uri-local-name [uri]
  (when (and uri (string? uri))
    (let [parts (str/split uri #":")]
      (last parts))))

(defn roles-vs-classes-pred
  "Fire when a concept's URI local-name MATCHES a role-shaped suffix
   (drawn from `role-shaped-class-suffixes`) — the membership-changes-
   over-time pattern usually warrants a ROLE modeling, not a CLASS
   modeling. Heuristic — emits :warning."
  [{:keys [concept]}]
  (let [local (str/lower-case (or (uri-local-name (:uri concept)) ""))
        match (some (fn [suffix]
                      (when (str/ends-with? local (str/lower-case suffix))
                        suffix))
                    role-shaped-class-suffixes)]
    (when match
      {:violation? true
       :detail (str "Class " (:uri concept)
                    " has role-shaped suffix " (pr-str match)
                    " — consider modeling as a Role (whose Person plays it)"
                    " rather than a Class.")})))

;; =============================================================================
;; S11 — Name-implied semantics (heuristic :code)
;; =============================================================================
;; If a class name parses as <Adjective><Noun> (e.g. ExpensiveCar,
;; LargeBuilding, RedDoor) but the class has no characteristic/
;; restriction asserting the adjective's referent (e.g. no link to a
;; price/size/color property), flag as advisory.

(def adjective-prefixes
  "Surface-form list flagging adjective-prefixed class names. Like
   `role-shaped-class-suffixes` this is declarative data the lint
   consults — NOT a hardcoded production gate. Consumers can supply
   their own variant via :code-symbol."
  ["expensive" "cheap" "large" "small" "big" "tiny" "tall" "short"
   "long" "fast" "slow" "old" "new" "young" "modern" "ancient"
   "red" "blue" "green" "black" "white" "bright" "dark"
   "happy" "sad" "rich" "poor" "smart" "dumb"])

(defn- camel-split
  "Split CamelCase into a vec of lowercase segments.
   `\"ExpensiveCar\"` -> `[\"expensive\" \"car\"]`. Returns nil when
   no CamelCase boundary exists."
  [s]
  (when (and (string? s) (seq s))
    (let [parts (re-seq #"[A-Z][a-z0-9]*" s)]
      (when (>= (count parts) 2)
        (mapv str/lower-case parts)))))

(defn name-implied-semantics-pred
  "Fire (advisory :info) when the concept's URI local-name parses as
   <Adjective><Noun> with the adjective in `adjective-prefixes`, AND
   the concept has no :attributes describing the adjective's
   referent (no `:has-magnitude`, no `:has-color`, etc. — checked as
   :attributes presence). Heuristic — emits :info."
  [{:keys [concept]}]
  (let [local (uri-local-name (:uri concept))
        segs (camel-split local)]
    (when segs
      (let [adj (first segs)
            attrs (or (:attributes concept) {})]
        (when (and (some #(= adj (str/lower-case %)) adjective-prefixes)
                   (empty? attrs))
          {:violation? true
           :detail (str "Class name " (pr-str local)
                        " parses as <Adjective><Noun> with adjective "
                        (pr-str adj)
                        " but the class has no :attributes describing the"
                        " adjective's referent. Consider naming the property"
                        " separately or adding a characteristic.")})))))

;; =============================================================================
;; S11 — Functional-property double values
;; =============================================================================
;; CRITICAL: this is a LINT — it does NOT cause silent sameAs inference
;; or value-merge. The projection retains BOTH values; the violation
;; surfaces as a queryable lint event. Tested adversarially in the S11
;; suite (post-run projection has both values; one lint event landed).
;;
;; Consumes :ontology/property-characteristic-asserted events with the
;; :functional characteristic. Counts edges on the relationships
;; projection per (source-uri, predicate). >=2 edges on any functional
;; predicate fires the lint with offending-uri = source URI.
;;
;; Edge-counting strategy: per-concept, walk :typed-edges <pred> set
;; AND the matching SKOS-collapsed key (:related/:broader/:narrower)
;; for the (typically uncommon) case where the functional predicate is
;; one of those. The relationships projection is the ground truth for
;; counting; the concept-level walk is a fast path.

(defn- functional-predicates
  "Return the SET of predicates marked :functional in the axioms map."
  [axioms-map]
  (let [chars (:characteristics axioms-map)]
    (set (keep (fn [[pred flags]]
                 (when (contains? flags :functional) pred))
               chars))))

(defn functional-double-value-pred
  "Fire when this concept has >=2 distinct VALUES on any predicate
   marked :functional in the axiom projection. The projection is NOT
   mutated — both values remain present (verified by the test); the
   violation surfaces ONLY as a lint event."
  [{:keys [concept ctx]}]
  (let [funcs (functional-predicates (:axioms ctx))]
    (when (seq funcs)
      ;; Source-side edge count: walk :typed-edges <pred> sets AND
      ;; SKOS-collapsed buckets (when the predicate aliases to one)
      (let [typed-edges (or (:typed-edges concept) {})
            offenders
            (->> funcs
                 (keep (fn [pred]
                         (let [from-typed (or (get typed-edges pred) #{})
                               from-skos (case pred
                                           "skos:broader"  (or (:broader concept) #{})
                                           "skos:narrower" (or (:narrower concept) #{})
                                           "skos:related"  (or (:related concept) #{})
                                           #{})
                               values (clojure.set/union (set from-typed)
                                                         (set from-skos))]
                           (when (>= (count values) 2)
                             [pred (vec (sort values))]))))
                 vec)]
        (when (seq offenders)
          {:violation? true
           :detail (str "Functional predicate(s) on " (:uri concept)
                        " carry multiple values (lint violation, NOT silent sameAs): "
                        (pr-str offenders))})))))

;; =============================================================================
;; S11 — Single-parent discipline
;; =============================================================================

(defn single-parent-discipline-pred
  "Fire when a concept has >1 :broader parent AND no
   :attributes/:multi-inheritance? true marker. Warns at the
   ontology-normalisation discipline level."
  [{:keys [concept]}]
  (let [parents (:broader concept)
        explicit-multi? (boolean (get-in concept [:attributes :multi-inheritance?]))]
    (when (and (> (count parents) 1) (not explicit-multi?))
      {:violation? true
       :detail (str "Concept " (:uri concept)
                    " asserts " (count parents) " broader-parents "
                    (pr-str (vec (sort parents)))
                    " — single-parent discipline recommended unless"
                    " :attributes/:multi-inheritance? is set true.")})))

;; =============================================================================
;; Shape data — what callers register (S10 + S11)
;; =============================================================================

;; --- S10 ---

(def dangling-endpoint-shape
  "Lint 1 — Every concept of :custom scope that has a :skos:related
   edge must have ALL its related-objects resolve to concepts in the
   graph. Standard-SHACL-expressible (via :property + :not)."
  {:shape/id     :ontology.lint/dangling-endpoint
   :shape/type   :node-shape
   :target-class :custom
   :severity     :violation
   :message      "Relationship endpoint does not resolve to a known concept."
   :deactivated  false
   :property [{:path      "skos:related"
               :min-count 0
               :not       {:object-exists? false}}]})

(def naming-convention-shape
  "Lint 2 — :code escape hatch (in-process)."
  {:shape/id     :ontology.lint/naming-convention
   :shape/type   :node-shape
   :target-class :custom
   :severity     :violation
   :message      "URI does not follow prefix:section:Name naming convention."
   :deactivated  false
   :code         naming-convention-pred})

(def naming-convention-shape-symbol
  "Persistable variant of the naming-convention shape — references the
   predicate via :code-symbol so the shape body survives EDN
   serialization into the event store."
  {:shape/id     :ontology.lint/naming-convention
   :shape/type   :node-shape
   :target-class :custom
   :severity     :violation
   :message      "URI does not follow prefix:section:Name naming convention."
   :deactivated  false
   :code-symbol  'ai.obney.orc.ontology.core.lints.builtin/naming-convention-pred})

(def language-tag-misuse-shape
  "Lint 3 — :code escape hatch (in-process)."
  {:shape/id     :ontology.lint/language-tag-misuse
   :shape/type   :node-shape
   :target-class :custom
   :severity     :warning
   :message      "Label carries a language tag but the value is non-linguistic."
   :deactivated  false
   :code         language-tag-misuse-pred})

(def language-tag-misuse-shape-symbol
  "Persistable variant — see `naming-convention-shape-symbol`."
  {:shape/id     :ontology.lint/language-tag-misuse
   :shape/type   :node-shape
   :target-class :custom
   :severity     :warning
   :message      "Label carries a language tag but the value is non-linguistic."
   :deactivated  false
   :code-symbol  'ai.obney.orc.ontology.core.lints.builtin/language-tag-misuse-pred})

;; --- S11 ---

(def disjointness-violation-shape
  "S11 Lint 4 — Concept typed under two classes asserted disjoint via
   S07 :ontology/disjointness-asserted events. :code reads the axiom
   projection through the interpreter's :ctx."
  {:shape/id     :ontology.lint/disjointness-violation
   :shape/type   :node-shape
   :target-class :custom
   :severity     :violation
   :message      "Concept is typed under two classes asserted mutually disjoint."
   :deactivated  false
   :code         disjointness-violation-pred})

(def disjointness-violation-shape-symbol
  {:shape/id     :ontology.lint/disjointness-violation
   :shape/type   :node-shape
   :target-class :custom
   :severity     :violation
   :message      "Concept is typed under two classes asserted mutually disjoint."
   :deactivated  false
   :code-symbol  'ai.obney.orc.ontology.core.lints.builtin/disjointness-violation-pred})

(def missing-disjointness-shape
  "S11 Lint 5 — Sibling-class set with no disjointness asserted."
  {:shape/id     :ontology.lint/missing-disjointness
   :shape/type   :node-shape
   :target-class :custom
   :severity     :warning
   :message      "Sibling classes share a parent but no disjointness is asserted."
   :deactivated  false
   :code         missing-disjointness-pred})

(def missing-disjointness-shape-symbol
  {:shape/id     :ontology.lint/missing-disjointness
   :shape/type   :node-shape
   :target-class :custom
   :severity     :warning
   :message      "Sibling classes share a parent but no disjointness is asserted."
   :deactivated  false
   :code-symbol  'ai.obney.orc.ontology.core.lints.builtin/missing-disjointness-pred})

(def universal-without-existential-shape
  "S11 Lint 6 — vacuously-satisfiable universal restriction pattern."
  {:shape/id     :ontology.lint/universal-without-existential
   :shape/type   :node-shape
   :target-class :custom
   :severity     :warning
   :message      "Universal restriction has no existential assertion (vacuously satisfiable)."
   :deactivated  false
   :code         universal-without-existential-pred})

(def universal-without-existential-shape-symbol
  {:shape/id     :ontology.lint/universal-without-existential
   :shape/type   :node-shape
   :target-class :custom
   :severity     :warning
   :message      "Universal restriction has no existential assertion (vacuously satisfiable)."
   :deactivated  false
   :code-symbol  'ai.obney.orc.ontology.core.lints.builtin/universal-without-existential-pred})

(def closure-axiom-absence-shape
  "S11 Lint 7 — complete enumeration without closing assertion. :info."
  {:shape/id     :ontology.lint/closure-axiom-absence
   :shape/type   :node-shape
   :target-class :custom
   :severity     :info
   :message      "Sibling group appears fully enumerated; parent has no closing assertion."
   :deactivated  false
   :code         closure-axiom-absence-pred})

(def closure-axiom-absence-shape-symbol
  {:shape/id     :ontology.lint/closure-axiom-absence
   :shape/type   :node-shape
   :target-class :custom
   :severity     :info
   :message      "Sibling group appears fully enumerated; parent has no closing assertion."
   :deactivated  false
   :code-symbol  'ai.obney.orc.ontology.core.lints.builtin/closure-axiom-absence-pred})

(def roles-vs-classes-shape
  "S11 Lint 8 — role-shaped class name. :warning. Heuristic — consults
   `role-shaped-class-suffixes` (declarative data, NOT a hardcoded
   production gate)."
  {:shape/id     :ontology.lint/roles-vs-classes
   :shape/type   :node-shape
   :target-class :custom
   :severity     :warning
   :message      "Class name has role-shaped suffix; consider modeling as a Role."
   :deactivated  false
   :code         roles-vs-classes-pred})

(def roles-vs-classes-shape-symbol
  {:shape/id     :ontology.lint/roles-vs-classes
   :shape/type   :node-shape
   :target-class :custom
   :severity     :warning
   :message      "Class name has role-shaped suffix; consider modeling as a Role."
   :deactivated  false
   :code-symbol  'ai.obney.orc.ontology.core.lints.builtin/roles-vs-classes-pred})

(def name-implied-semantics-shape
  "S11 Lint 9 — <Adjective><Noun> class name without backing characteristic. :info."
  {:shape/id     :ontology.lint/name-implied-semantics
   :shape/type   :node-shape
   :target-class :custom
   :severity     :info
   :message      "Class name implies a property the model does not assert."
   :deactivated  false
   :code         name-implied-semantics-pred})

(def name-implied-semantics-shape-symbol
  {:shape/id     :ontology.lint/name-implied-semantics
   :shape/type   :node-shape
   :target-class :custom
   :severity     :info
   :message      "Class name implies a property the model does not assert."
   :deactivated  false
   :code-symbol  'ai.obney.orc.ontology.core.lints.builtin/name-implied-semantics-pred})

(def functional-double-value-shape
  "S11 Lint 10 — two values on a :functional-marked property.
   CRITICAL: emitted as a lint VIOLATION; the projection state is
   UNCHANGED (no silent sameAs/merge). The S11 test asserts both."
  {:shape/id     :ontology.lint/functional-double-value
   :shape/type   :node-shape
   :target-class :custom
   :severity     :violation
   :message      "Functional property carries multiple distinct values (lint, NOT sameAs)."
   :deactivated  false
   :code         functional-double-value-pred})

(def functional-double-value-shape-symbol
  {:shape/id     :ontology.lint/functional-double-value
   :shape/type   :node-shape
   :target-class :custom
   :severity     :violation
   :message      "Functional property carries multiple distinct values (lint, NOT sameAs)."
   :deactivated  false
   :code-symbol  'ai.obney.orc.ontology.core.lints.builtin/functional-double-value-pred})

(def single-parent-discipline-shape
  "S11 Lint 11 — multi-parent assertion without explicit
   `:multi-inheritance? true` marker. :warning."
  {:shape/id     :ontology.lint/single-parent-discipline
   :shape/type   :node-shape
   :target-class :custom
   :severity     :warning
   :message      "Concept asserts multiple broader-parents without an explicit multi-inheritance marker."
   :deactivated  false
   :code         single-parent-discipline-pred})

(def single-parent-discipline-shape-symbol
  {:shape/id     :ontology.lint/single-parent-discipline
   :shape/type   :node-shape
   :target-class :custom
   :severity     :warning
   :message      "Concept asserts multiple broader-parents without an explicit multi-inheritance marker."
   :deactivated  false
   :code-symbol  'ai.obney.orc.ontology.core.lints.builtin/single-parent-discipline-pred})

;; =============================================================================
;; Bundle: M4 lint set (S10 + S11 — 11 lints total)
;; =============================================================================

(def all-builtin-shapes
  "The three S10 built-in lints in registration order. Uses
   code-symbol variants so a caller registering through an event-store
   sees shapes that round-trip. PRESERVED for back-compat — S11
   callers wanting the FULL M4 set should use `all-m4-builtin-shapes`."
  [dangling-endpoint-shape
   naming-convention-shape-symbol
   language-tag-misuse-shape-symbol])

(def all-m4-builtin-shapes
  "The full M4 built-in lint set — S10 (3) + S11 (8) = 11 lints. All
   in code-symbol form so registry round-tripping works."
  [dangling-endpoint-shape
   naming-convention-shape-symbol
   language-tag-misuse-shape-symbol
   disjointness-violation-shape-symbol
   missing-disjointness-shape-symbol
   universal-without-existential-shape-symbol
   closure-axiom-absence-shape-symbol
   roles-vs-classes-shape-symbol
   name-implied-semantics-shape-symbol
   functional-double-value-shape-symbol
   single-parent-discipline-shape-symbol])
