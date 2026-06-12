(ns ai.obney.orc.ontology.core.lints.interpreter
  "S10 — EDN-SHACL interpreter (phase-1 subset).

   The b' grill decision shipped: shapes are SHACL-shaped EDN, source-of-
   truth, Malli-validated at registration time, interpreted in-JVM, and
   ALL violations flow through the same event vocabulary so validation
   health is queryable history like everything else.

   This namespace is PURE — no event-store, no Grain. It takes a graph
   (the URI-keyed `:ontology/concepts` projection map) plus a sequence
   of shapes and returns `{:violations [...] :skips [...]}` records that
   the commands namespace wraps into `:ontology/lint-violation` and
   `:ontology/lint-shape-skipped` events.

   Phase-1 supported components (locked in this slice):
   - :target-class resolution — keyword (=> :scope match), URI prefix
     (string ending in `:`) , exact URI, or nil (=> match-all)
   - :property [:path :min-count :max-count :not]
   - :not v1 vocabulary — currently only {:object-exists? boolean}.
     Reads as English: `:not {:object-exists? false}` = violate when
     [object exists] is false = dangling endpoint.
   - :code escape hatch (in-process fn) — receives
     `{:graph :concept}`; returns nil for clean, `{:violation? :detail}`
     for violation.
   - :code-symbol — fully-qualified symbol resolved via requiring-resolve
     at run-time. Used for persisted shapes whose :code can't be
     EDN-serialized.
   - :severity, :message, :deactivated.

   What's NOT phase-1 (S11 inherits): qualified-value-shape,
   :not vocabulary expansion, nested node-shape composition, SHACL TTL
   round-trip."
  (:require [clojure.string :as str]))

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
   - string predicate that isn't a known edge → fall back to :related
     (the URI-keyed projection collapses unknown predicates here, so
     this matches what's projected)
   - keyword path → direct attribute lookup on the concept map (used by
     :labels, :indicators, :attributes — letting code-free shapes
     inspect node-attribute values)."
  [_graph concept path]
  (cond
    (string? path)
    (let [k (predicate->concept-key path :related)]
      (some-> concept k seq))

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
;; :not constraint
;; =============================================================================

(defn- not-violated?
  "Apply a v1 :not inner-predicate against `obj`. Returns true when the
   :not is VIOLATED (i.e., the inner predicate is satisfied — which the
   shape says it must NOT be).

   v1 vocabulary (Malli-enforced upstream):
     :object-exists? boolean
       Inner says 'object MUST exist' (true) or 'object must NOT exist'
       (false). The :not flips it. Concrete reading:
         :not {:object-exists? false}
           → violate when [object exists] is false  → dangling endpoint
         :not {:object-exists? true}
           → violate when [object exists] is true   → 'must-be-novel' lint"
  [graph not-shape obj]
  ;; NOTE: must NOT use `if-let` here — when :object-exists? is false,
  ;; the bound value is false and if-let falls through to the else
  ;; branch, which would throw. Branch on key presence explicitly.
  (cond
    (contains? not-shape :object-exists?)
    (let [needed (boolean (:object-exists? not-shape))
          in-graph? (contains? graph obj)]
      ;; :not is VIOLATED when the inner predicate is SATISFIED.
      ;; Inner says "object-exists? = needed". Satisfied when in-graph? = needed.
      ;; So violation when in-graph? = needed.
      (= needed in-graph?))

    :else
    ;; unknown inner-predicate → strict: throw so test surfaces the
    ;; not-yet-supported subset instead of silently passing.
    (throw (ex-info "Unsupported :not inner predicate (S10 v1 only knows :object-exists?)"
                    {:not-shape not-shape :phase :s10
                     :supported #{:object-exists?}}))))

;; =============================================================================
;; Property + code application
;; =============================================================================

(defn- apply-property
  "Run one property-constraint against `concept`. Return seq of partial
   violation records (each merged with shape-level fields by the caller)."
  [graph concept {:keys [path min-count max-count]
                  not-constraint :not}]
  (let [values (resolve-path graph concept path) ; nil OR seq of objects
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
                            " violates :not " (pr-str not-constraint))})))))

(defn- resolve-code-fn
  "Return the runnable fn for a shape's escape-hatch predicate, OR nil.

   Precedence: explicit `:code` fn wins over `:code-symbol` (the in-process
   form is the most direct). If only `:code-symbol` is supplied, we
   resolve via requiring-resolve so the namespace is loaded on first use.
   An unresolved :code-symbol THROWS (don't silently skip — root-cause
   discipline)."
  [{:keys [code code-symbol] :as _shape}]
  (cond
    (fn? code)
    code

    (symbol? code-symbol)
    (or (requiring-resolve code-symbol)
        (throw (ex-info "Could not resolve :code-symbol"
                        {:code-symbol code-symbol
                         :phase :s10})))

    :else
    nil))

(defn- apply-code
  "Run the :code / :code-symbol predicate against the concept. Returns
   0 or 1 partial violation record."
  [graph concept shape]
  (when-let [code-fn (resolve-code-fn shape)]
    (let [result (code-fn {:graph graph :concept concept})]
      (when (and (map? result) (:violation? result))
        [{:reason :code-predicate-rejected
          :detail (or (:detail result) "code predicate returned :violation? true")}]))))

(defn- evaluate-shape-on-concept
  "Apply one shape to one concept; returns seq of fully-shaped violation
   records (shape-id + severity + message + offending-uri + reason +
   detail)."
  [graph shape concept]
  (let [{shape-id :shape/id
         :keys [severity message property]} shape
        partials (concat (mapcat #(apply-property graph concept %) (or property []))
                         (apply-code graph concept shape))]
    (mapv (fn [p]
            (merge {:shape-id shape-id
                    :severity severity
                    :message message
                    :offending-uri (:uri concept)}
                   p))
          partials)))

;; =============================================================================
;; Public surface
;; =============================================================================

(defn run-shape
  "Run one shape against the whole graph.
   Returns `{:violations [...] :skips [...]}`."
  [graph {:keys [deactivated target-class] :as shape}]
  (if deactivated
    {:violations []
     :skips [{:shape-id (:shape/id shape) :reason :deactivated}]}
    (let [targets (resolve-target-class graph target-class)]
      {:violations (vec (mapcat #(evaluate-shape-on-concept graph shape %) targets))
       :skips []})))

(defn run-registry
  "Run a seq of shapes against the graph. Returns one merged
   `{:violations [...] :skips [...]}` map."
  [graph shapes]
  (reduce (fn [acc shape]
            (let [{:keys [violations skips]} (run-shape graph shape)]
              (-> acc
                  (update :violations into violations)
                  (update :skips into skips))))
          {:violations [] :skips []}
          shapes))
