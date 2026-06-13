(ns ai.obney.orc.ontology.core.lints.interpreter
  "S10 + S11 — EDN-SHACL interpreter.

   The b' grill decision shipped: shapes are SHACL-shaped EDN, source-of-
   truth, Malli-validated at registration time, interpreted in-JVM, and
   ALL violations flow through the same event vocabulary so validation
   health is queryable history like everything else.

   This namespace is PURE — no event-store, no Grain. It takes a graph
   (the URI-keyed `:ontology/concepts` projection map) — or a richer
   `:graph + :axioms + :relationships` context — plus a sequence of
   shapes and returns `{:violations [...] :skips [...]}` records that the
   commands namespace wraps into `:ontology/lint-violation` and
   `:ontology/lint-shape-skipped` events.

   --- Phase 1 (S10) components ---
   - :target-class resolution — keyword (=> :scope match), URI prefix
     (string ending in `:`) , exact URI, or nil (=> match-all)
   - :property [:path :min-count :max-count :not]
   - :not v1 vocabulary: `{:object-exists? boolean}`.
     Reads as English: `:not {:object-exists? false}` = violate when
     [object exists] is false = dangling endpoint.
   - :code escape hatch (in-process fn) — receives
     `{:graph :concept :ctx}`; returns nil for clean, `{:violation? :detail}`
     for violation. NEW in S11: `:ctx` exposes :axioms + :relationships so
     axiom-consuming :code lints can read the join.
   - :code-symbol — fully-qualified symbol resolved via requiring-resolve
     at run-time.
   - :severity, :message, :deactivated.

   --- Phase 2 (S11) additions ---
   - :not vocabulary extended:
       :datatype <type-keyword/string>
       :datatype-match? boolean  (default true)
         Inner-predicate true when object's datatype equals <type>.
         :not flips it. Practical reading on a graph where objects are
         either Clojure values (numbers/strings) or URIs:
           :not {:datatype :number}                ; violate when number
           :not {:datatype :number :datatype-match? false}
                                                   ; violate when NOT number
       :pattern <regex-string-or-Pattern>
       :pattern-match? boolean   (default true)
         Inner-predicate true when (str obj) matches the regex.
         :not flips it.
   - :qualified-value-shape — a nested NodeShape applied to each value
     reached by the property path. The MIN-count of values that conform
     to the nested shape must be >= :qualified-min-count. The shape's
     nested target-class (when present) is RESPECTED for filtering before
     conformance check — `sh:qualifiedValueShape` semantic. Adversarial
     test: a value that doesn't conform produces ZERO contribution to the
     conformant-count; getting fewer than :qualified-min-count emits
     `:qualified-min-count-violated`."
  (:require [clojure.string :as str]))

;; =============================================================================
;; Context normalization — accept either a bare graph map OR a context
;; map carrying graph + axioms + relationships. Backward-compat: every
;; S10 caller that hands in a bare {uri -> concept-map} still works.
;; =============================================================================

(defn- ->ctx
  "Coerce the runner's first arg into a normalized validation context map.

   Accepts:
   - a bare graph map (S10 callers) — wraps as {:graph g :axioms nil :relationships nil}
   - an already-shaped {:graph ... :axioms ... :relationships ...} map

   The shape check looks for the :graph key, which is unambiguous against
   a concept map (whose keys are URIs)."
  [graph-or-ctx]
  (if (and (map? graph-or-ctx) (contains? graph-or-ctx :graph))
    graph-or-ctx
    {:graph graph-or-ctx :axioms nil :relationships nil}))

;; =============================================================================
;; Path + target resolution
;; =============================================================================

(defn- resolve-target-class
  "Return the seq of concepts in `graph` that match `target-class`.

   - nil       → every concept
   - keyword   → concepts whose :scope equals it
   - string  →
       - ending in `:` → URI prefix match
       - otherwise     → exact URI match
   - anything else → empty seq (defensive — Malli already rejected)."
  [graph target-class]
  (cond
    (nil? target-class)
    (vals graph)

    (keyword? target-class)
    (filter #(= target-class (:scope %)) (vals graph))

    (string? target-class)
    (if (str/ends-with? target-class ":")
      (filter #(str/starts-with? (:uri %) target-class) (vals graph))
      (filter #(= target-class (:uri %)) (vals graph)))

    :else
    []))

;; The URI-keyed concepts projection collapses the relationship vocabulary
;; onto the concept-map's :broader / :narrower / :related (and a few other)
;; sets — see read_models.clj concepts*. For S10 we resolve string-predicates
;; to those sets, which keeps the interpreter aligned with how the
;; projection already exposes edges.
(def ^:private predicate->concept-key
  {"skos:broader"            :broader
   "skos:narrower"           :narrower
   "skos:related"            :related
   "behavior:composes-into"  :composes-into
   "behavior:composed-by"    :composed-by})

(defn- resolve-path
  "Return the seq of values reached by walking `path` from `concept`.

   - string predicate that's a known SKOS/behavior edge → look up the
     corresponding edge-set on the concept map
   - string predicate that isn't a known edge → look up :typed-edges
     <predicate> first (S11 surfaces typed predicates here, the same
     bucket the projection stores them in); fall back to :related when
     no typed bucket exists.
   - keyword path → direct attribute lookup on the concept map (used by
     :labels, :indicators, :attributes — letting code-free shapes
     inspect node-attribute values)."
  [_graph concept path]
  (cond
    (string? path)
    (if-let [k (predicate->concept-key path)]
      (some-> concept k seq)
      ;; Unknown string predicate — prefer the typed-edges bucket
      ;; (S11: lets shapes target predicates like ex:hasAge that don't
      ;; map onto :broader/:narrower/:related). Falls back to :related
      ;; for back-compat with S10 shapes that used unknown strings to
      ;; mean "the related bucket".
      (or (some-> concept :typed-edges (get path) seq)
          (some-> concept :related seq)))

    (keyword? path)
    (let [v (get concept path)]
      (cond
        (nil? v) nil
        (set? v) (seq v)
        (sequential? v) (seq v)
        :else [v]))

    :else
    nil))

;; =============================================================================
;; :not constraint — S10 + S11 vocabulary
;; =============================================================================

(defn- value-datatype
  "Classify an object's runtime datatype for :datatype matching.

   The lint operates on the URI-keyed projection where objects are
   either:
   - URIs (strings that look like URIs — present in :broader/:related etc.)
   - literal numbers
   - literal strings (other than URI-shaped strings)

   Returns a keyword: :uri, :number, :string, :boolean, :other."
  [obj]
  (cond
    (number? obj) :number
    (boolean? obj) :boolean
    (and (string? obj) (re-matches #"^[A-Za-z][A-Za-z0-9_-]*:.*" obj)) :uri
    (string? obj) :string
    :else :other))

(defn- not-violated?
  "Apply a :not inner-predicate against `obj`. Returns true when the
   :not is VIOLATED (i.e., the inner predicate is satisfied — which the
   shape says it must NOT be).

   v1 vocabulary (S10):
     :object-exists? boolean
       Inner says 'object MUST exist' (true) or 'object must NOT exist'
       (false). The :not flips it. Concrete reading:
         :not {:object-exists? false}
           → violate when [object exists] is false  → dangling endpoint
         :not {:object-exists? true}
           → violate when [object exists] is true   → 'must-be-novel' lint

   v2 vocabulary (S11):
     :datatype  <:number :string :uri :boolean :other>
     :datatype-match? boolean (default true)
       Inner says 'object's datatype = <given>' when match? true; the
       :not flips it. Reading:
         :not {:datatype :number}
           → violate when datatype IS :number
         :not {:datatype :number :datatype-match? false}
           → violate when datatype is NOT :number

     :pattern  string-or-Pattern
     :pattern-match? boolean (default true)
       Inner says '(re-find pattern (str obj))' truthy when match? true."
  [graph not-shape obj]
  (cond
    (contains? not-shape :object-exists?)
    (let [needed (boolean (:object-exists? not-shape))
          in-graph? (contains? graph obj)]
      ;; :not VIOLATED when the inner predicate is SATISFIED.
      (= needed in-graph?))

    (contains? not-shape :datatype)
    (let [needed-type (:datatype not-shape)
          match? (if (contains? not-shape :datatype-match?)
                   (boolean (:datatype-match? not-shape))
                   true)
          actual (value-datatype obj)
          equal? (= actual needed-type)
          ;; Inner predicate: equal? when match?=true, else (not equal?)
          inner-satisfied? (if match? equal? (not equal?))]
      inner-satisfied?)

    (contains? not-shape :pattern)
    (let [pattern (:pattern not-shape)
          re (if (instance? java.util.regex.Pattern pattern)
               pattern
               (re-pattern (str pattern)))
          match? (if (contains? not-shape :pattern-match?)
                   (boolean (:pattern-match? not-shape))
                   true)
          found? (boolean (re-find re (str obj)))
          inner-satisfied? (if match? found? (not found?))]
      inner-satisfied?)

    :else
    ;; unknown inner-predicate → strict: throw so test surfaces the
    ;; not-yet-supported subset instead of silently passing.
    (throw (ex-info "Unsupported :not inner predicate"
                    {:not-shape not-shape
                     :supported #{:object-exists? :datatype :pattern}}))))

;; =============================================================================
;; Property + code application
;; =============================================================================

(declare evaluate-shape-on-concept-internal)

(defn- nested-conforms?
  "Return true when `obj` conforms to the nested `shape` (S11
   qualified-value-shape).

   Conformance check: the value, treated as a synthetic concept (URI
   resolution if the value names a concept in the graph; otherwise a
   bare {:uri value} placeholder), is checked against the inner shape's
   :property + :code constraints. ZERO violations from the inner shape
   means the value conforms.

   A nested :target-class is treated as a FILTER: if the resolved
   concept's scope/uri doesn't match, the value is treated as NOT
   conforming (not an error — qualifiedValueShape's filter semantic)."
  [ctx shape obj]
  (let [graph (:graph ctx)
        ;; Try to resolve the obj as a URI in the graph
        resolved (get graph obj)
        as-concept (or resolved {:uri obj :scope nil :broader #{} :narrower #{} :related #{}})
        target-class (:target-class shape)
        target-ok? (cond
                     (nil? target-class) true
                     (keyword? target-class) (= target-class (:scope as-concept))
                     (string? target-class)
                     (if (str/ends-with? target-class ":")
                       (and (:uri as-concept)
                            (str/starts-with? (:uri as-concept) target-class))
                       (= target-class (:uri as-concept)))
                     :else false)]
    (if-not target-ok?
      false
      (let [results (evaluate-shape-on-concept-internal ctx shape as-concept)]
        (empty? results)))))

(defn- apply-property
  "Run one property-constraint against `concept`. Return seq of partial
   violation records (each merged with shape-level fields by the caller)."
  [ctx concept {:keys [path min-count max-count
                       qualified-value-shape qualified-min-count]
                not-constraint :not}]
  (let [graph (:graph ctx)
        values (resolve-path graph concept path)
        cnt (count (or values []))
        records (cond-> []
                  (and min-count (< cnt min-count))
                  (conj {:reason :min-count-violated
                         :detail (str "got " cnt " value(s) for path " (pr-str path)
                                      " (min " min-count ")")})

                  (and max-count (> cnt max-count))
                  (conj {:reason :max-count-violated
                         :detail (str "got " cnt " value(s) for path " (pr-str path)
                                      " (max " max-count ")")}))]
    (cond-> records
      not-constraint
      (into (for [obj (or values [])
                  :when (not-violated? graph not-constraint obj)]
              {:reason :not-constraint-violated
               :detail (str "value " (pr-str obj) " for path " (pr-str path)
                            " violates :not " (pr-str not-constraint))}))

      qualified-value-shape
      (#(let [matching (count (filter (partial nested-conforms? ctx qualified-value-shape)
                                       (or values [])))
              required (or qualified-min-count 1)]
          (if (< matching required)
            (conj % {:reason :qualified-min-count-violated
                     :detail (str "got " matching " value(s) matching qualified shape "
                                  (pr-str (:shape/id qualified-value-shape "<inline>"))
                                  " for path " (pr-str path)
                                  " (min " required ")")})
            %))))))

(defn- resolve-code-fn
  "Return the runnable fn for a shape's escape-hatch predicate, OR nil.

   Precedence: explicit `:code` fn wins over `:code-symbol`. If only
   `:code-symbol` is supplied, we resolve via requiring-resolve. An
   unresolved :code-symbol THROWS."
  [{:keys [code code-symbol] :as _shape}]
  (cond
    (fn? code)
    code

    (symbol? code-symbol)
    (or (requiring-resolve code-symbol)
        (throw (ex-info "Could not resolve :code-symbol"
                        {:code-symbol code-symbol})))

    :else
    nil))

(defn- apply-code
  "Run the :code / :code-symbol predicate against the concept. Returns
   0 or 1 partial violation record.

   S11: the predicate receives `{:graph :concept :ctx}` where :ctx
   exposes :axioms + :relationships so axiom-consuming lints (functional
   double-value, disjointness, etc.) can read the join. S10 predicates
   that only consult :graph + :concept continue to work — the extra :ctx
   key is additive."
  [ctx concept shape]
  (when-let [code-fn (resolve-code-fn shape)]
    (let [result (code-fn {:graph (:graph ctx)
                           :concept concept
                           :ctx ctx})]
      (when (and (map? result) (:violation? result))
        [{:reason :code-predicate-rejected
          :detail (or (:detail result) "code predicate returned :violation? true")}]))))

(defn- evaluate-shape-on-concept-internal
  "Apply one shape to one concept; returns seq of fully-shaped violation
   records (shape-id + severity + message + offending-uri + reason +
   detail). The `ctx` arg is the normalized validation context."
  [ctx shape concept]
  (let [{shape-id :shape/id
         :keys [severity message property]} shape
        partials (concat (mapcat #(apply-property ctx concept %) (or property []))
                         (apply-code ctx concept shape))]
    (mapv (fn [p]
            (merge {:shape-id shape-id
                    :severity severity
                    :message message
                    :offending-uri (:uri concept)}
                   p))
          partials)))

(defn evaluate-shape-on-concept
  "Public wrapper retained for back-compat (S10 callers passed a graph).
   `graph-or-ctx` accepts either a bare graph map OR a normalized ctx."
  [graph-or-ctx shape concept]
  (evaluate-shape-on-concept-internal (->ctx graph-or-ctx) shape concept))

;; =============================================================================
;; Public surface
;; =============================================================================

(defn run-shape
  "Run one shape against the whole graph.
   Returns `{:violations [...] :skips [...]}`.

   `graph-or-ctx` accepts either a raw graph map (S10) or a {:graph
   :axioms :relationships} context (S11+)."
  [graph-or-ctx {:keys [deactivated target-class] :as shape}]
  (if deactivated
    {:violations []
     :skips [{:shape-id (:shape/id shape) :reason :deactivated}]}
    (let [ctx (->ctx graph-or-ctx)
          targets (resolve-target-class (:graph ctx) target-class)]
      {:violations (vec (mapcat #(evaluate-shape-on-concept-internal ctx shape %) targets))
       :skips []})))

(defn run-registry
  "Run a seq of shapes against the graph. Returns one merged
   `{:violations [...] :skips [...]}` map."
  [graph-or-ctx shapes]
  (let [ctx (->ctx graph-or-ctx)]
    (reduce (fn [acc shape]
              (let [{:keys [violations skips]} (run-shape ctx shape)]
                (-> acc
                    (update :violations into violations)
                    (update :skips into skips))))
            {:violations [] :skips []}
            shapes)))
