(ns ai.obney.orc.orc-service.core.sandbox-tools
  "S19 — RLM ontology tools exposed as sandbox primitives.

   The builder-facing subset of the ontology retrieval surface that a
   recursive-RLM session can call as SCI bindings. Each tool wraps the
   ai.obney.orc.ontology.interface fn(s) under a shape designed for
   model-authored code — concrete argument order, a self-contained
   docstring (purpose + worked example + return shape), and ISOLATION
   that's enforced INSIDE the tool rather than trusted to the model.

   The seven tools, in the order they appear here:

   - graph-search           — scoped hybrid retrieval (BFS + embedding
                              + ColBERT via RRF) honoring S02 scoping +
                              S03 auto-widening.
   - neighborhood           — BFS expansion around a seed URI; the
                              DESCRIBE-equivalent.
   - get-concept            — single-concept body lookup (labels,
                              attributes, annotations, evidence).
   - exists?                — cheap closed-world ASK over the
                              concepts projection.
   - absent-in-graph?       — closed-world negation over the
                              relationships projection (the
                              deterministic SPARQL-NOT-EXISTS analogue;
                              see Cat 6 capability map).
   - filter-by-label-pattern — regex / substring filter over labels
                              of a supplied URI seq.
   - classify-task /
     classify-behaviors     — the existing classifier surface as tools.

   ## Isolation invariant (adversarial requirement)

   The granted ontology-id (passed in via :granted-ontology-id at sandbox-
   build time) is the AUTHORITATIVE scope. The model can ask for a
   different :ontology-id in any call; the tools IGNORE that argument
   and use the grant. S03 auto-widening still runs THROUGH the registry
   from the GRANTED id — alignment-section content surfaces, but only
   for sections registered AS alignments of the granted scope. When an
   alignment is deregistered, the next call immediately loses access.

   ## Read-side only

   Every tool projects existing events; none emits events. The R05c
   precedent (mint-behavior!) is a write-side primitive and lives in
   rlm-sandbox; these are deliberately separate.

   ## Wiring

   `build-ontology-tool-bindings` returns the {symbol → fn} map a SCI
   sandbox can merge into its bindings. `rlm-sandbox/build-rlm-context`
   calls this when its caller passes `:granted-ontology-id` (or
   `:granted-ontology-ids`) plus an `:event-store`. Without those, the
   tools are NOT exposed — the sandbox falls back to its existing
   primitive set unchanged."
  (:require [clojure.string :as str]))

;; =============================================================================
;; Lazy ontology resolution
;; =============================================================================
;; orc-service does not depend on ontology (ontology depends on orc-service).
;; We use requiring-resolve at call time, mirroring the existing precedent
;; in rlm-sandbox.clj's get-description binding (line 677).

(defn- ont-fn
  "Resolve an ontology-interface var lazily. Throws a clear error when
   ontology isn't on the classpath (test scaffolds without it should
   fail loudly rather than silently no-op)."
  [sym]
  (or (requiring-resolve sym)
      (throw (ex-info (str "S19 ontology tool requires the ontology component "
                           "on the classpath. Could not resolve: " sym)
                      {:symbol sym}))))

(defn- resolve-granted-scope
  "Coerce the build-time scope grant into a {:ontology-ids #{...}} map.

   The grant authoritative — the tools NEVER use the model-supplied
   :ontology-id. Returns nil when nothing was granted (caller should
   not have exposed the tools in that case)."
  [{:keys [granted-ontology-id granted-ontology-ids]}]
  (cond
    (seq granted-ontology-ids)
    {:ontology-ids (set granted-ontology-ids)}

    granted-ontology-id
    {:ontology-ids #{granted-ontology-id}}

    :else nil))

(defn- widen-scope
  "Walk the granted scope through the alignment-section registry at CALL
   time (not at sandbox-build time). This is what guarantees no
   staleness — deregistering an alignment loses access on the very
   next call.

   `ctx` is the read-model context the tools were given.
   `granted-ids` is the set from the resolve-granted-scope grant.
   Returns a SET of ontology-ids: the granted ids plus any of their
   currently-registered alignments. Empty input returns empty output."
  [ctx granted-ids]
  (let [widen (ont-fn 'ai.obney.orc.ontology.interface/widen-ontology-ids)]
    (set (widen ctx granted-ids))))

(defn- strip-model-scope
  "Remove any model-supplied :ontology-id / :ontology-ids from an opts
   map. The grant — not the argument — is authoritative."
  [opts]
  (dissoc opts :ontology-id :ontology-ids))

;; =============================================================================
;; Tool: graph-search
;; =============================================================================

(defn- make-graph-search-fn
  [{:keys [event-store tenant-id cache] :as cfg}]
  (let [granted (:ontology-ids (resolve-granted-scope cfg))
        hybrid-search (ont-fn 'ai.obney.orc.ontology.interface/hybrid-search)
        ;; hybrid-search's first arg is named `event-store` in its
        ;; docstring but in practice the implementation uses it as the
        ;; ctx map for downstream signal helpers (embeddings + ColBERT).
        ;; We pass the full ctx so cache + tenant-id are available.
        ctx (cond-> {:event-store event-store}
              tenant-id (assoc :tenant-id tenant-id)
              cache     (assoc :cache cache))]
    (fn graph-search
      ([query-text]
       (graph-search query-text {}))
      ([query-text opts]
       (let [clean (strip-model-scope (or opts {}))
             ;; Grant wins. We pass the bare granted ids — hybrid-search's
             ;; built-in auto-widen path (default-on) will then expand
             ;; through the alignment-section registry, so any
             ;; deregistration that happened since sandbox-build is
             ;; honored on this very call.
             merged (merge clean {:query-text query-text
                                  :ontology-ids granted})]
         (hybrid-search ctx merged))))))

(def graph-search-doc
  "PURPOSE — Hybrid retrieval over the ontology graph (BFS + embedding +
   ColBERT, RRF-fused) restricted to your granted scope. Use when you
   want best-overall matches for a natural-language query and you don't
   already know a specific URI.

   EXAMPLE
     (graph-search \"directors who started in 2010s\")
     ;; => {:results [{:uri \"concept:dir/jane-roe\" :score 0.78
     ;;                :label \"Jane Roe\" :description \"...\"
     ;;                :graph-rank 1 :embedding-rank 3 :colbert-rank 2}
     ;;               ...]
     ;;     :graph-results [...]
     ;;     :embedding-results [...]
     ;;     :colbert-results [...]
     ;;     :method \"rrf\"}

     ;; Optional second arg accepts: :limit, :min-similarity,
     ;; :max-depth, :decay, :seed-uris, :signals. NOT :ontology-id —
     ;; the grant is authoritative.
     (graph-search \"compliance flags\" {:limit 5})

   RETURNS — {:results [{:uri :score :label :description ...} ...]
              :graph-results [...] :embedding-results [...]
              :colbert-results [...]}

   SCOPE — automatically scoped to your granted ontology section(s).
   Any :ontology-id / :ontology-ids you pass is IGNORED — the grant is
   authoritative. S03 alignment-section auto-widening expands the grant
   through the registry, so content from registered alignment sections
   surfaces.")

;; =============================================================================
;; Tool: neighborhood
;; =============================================================================

(defn- make-neighborhood-fn
  [{:keys [event-store tenant-id cache] :as cfg}]
  (let [granted (:ontology-ids (resolve-granted-scope cfg))
        expand (ont-fn 'ai.obney.orc.ontology.interface/expand-concept-neighborhood)
        ctx (cond-> {:event-store event-store}
              tenant-id (assoc :tenant-id tenant-id)
              cache     (assoc :cache cache))]
    (fn neighborhood
      ([uri]
       (neighborhood uri {}))
      ([uri opts]
       (let [clean (strip-model-scope (or opts {}))
             max-depth (get clean :max-depth 2)
             decay (get clean :decay 0.6)
             seeds (if (coll? uri) (vec uri) [uri])
             ;; Widen at CALL time so deregistration is honored.
             effective (widen-scope ctx granted)]
         (expand seeds
                 :ctx ctx
                 :max-depth max-depth
                 :decay decay
                 :ontology-ids effective))))))

(def neighborhood-doc
  "PURPOSE — BFS expand outward from a known seed URI (or seq of URIs)
   to find graph-adjacent concepts. The DESCRIBE-equivalent — use this
   when you already have a URI and want its surroundings.

   EXAMPLE
     (neighborhood \"concept:dir/jane-roe\")
     ;; => [{:uri \"concept:dir/jane-roe\" :score 1.0 :path [] :depth 0}
     ;;     {:uri \"concept:film/red-dawn\" :score 0.6 :path [...] :depth 1}
     ;;     {:uri \"concept:role/director\" :score 0.6 :path [...] :depth 1}]

     ;; Optional second arg accepts: :max-depth (default 2),
     ;; :decay (default 0.6). NOT :ontology-id — the grant is authoritative.
     (neighborhood \"concept:dir/jane-roe\" {:max-depth 3 :decay 0.5})

   RETURNS — vector of {:uri :score :path :depth} sorted by score
   (descending).

   SCOPE — automatically scoped to your granted ontology section(s).
   Any :ontology-id you pass is IGNORED — the grant is authoritative.")

;; =============================================================================
;; Tool: get-concept
;; =============================================================================

(defn- make-get-concept-fn
  [{:keys [event-store tenant-id cache] :as cfg}]
  (let [granted (:ontology-ids (resolve-granted-scope cfg))
        get-by-uri (ont-fn 'ai.obney.orc.ontology.interface/get-concept-by-uri)
        ctx (cond-> {:event-store event-store}
              tenant-id (assoc :tenant-id tenant-id)
              cache     (assoc :cache cache))]
    (fn get-concept [uri]
      ;; Widen at CALL time so a freshly-registered alignment surfaces
      ;; immediately and a freshly-deregistered one disappears.
      (let [effective (widen-scope ctx granted)]
        (get-by-uri ctx uri {:ontology-ids effective})))))

(def get-concept-doc
  "PURPOSE — Look up the full body of a single concept by URI: every
   label, attribute, annotation, broader-set, scope, evidence metadata.
   Use when you have a URI and want everything the graph knows about it.

   EXAMPLE
     (get-concept \"concept:dir/jane-roe\")
     ;; => {:uri \"concept:dir/jane-roe\"
     ;;     :label \"Jane Roe\"
     ;;     :description \"Independent film director, debut 2014.\"
     ;;     :scope :person
     ;;     :broader [\"concept:role/director\"]
     ;;     :attributes {:birth-year 1981 :nationality \"GB\"}
     ;;     :labels [{:value \"Jane Roe\" :lang \"en\"}]
     ;;     :ontology-id #uuid \"...\"}

   RETURNS — concept map (keys above), or nil if the URI is not
   present in your scoped projection.

   SCOPE — automatically scoped to your granted ontology section(s).
   Any :ontology-id you pass is IGNORED — the grant is authoritative.")

;; =============================================================================
;; Tool: exists?
;; =============================================================================

(defn- make-exists-fn
  [{:keys [event-store tenant-id cache] :as cfg}]
  (let [granted (:ontology-ids (resolve-granted-scope cfg))
        get-by-uri (ont-fn 'ai.obney.orc.ontology.interface/get-concept-by-uri)
        ctx (cond-> {:event-store event-store}
              tenant-id (assoc :tenant-id tenant-id)
              cache     (assoc :cache cache))]
    (fn exists? [uri]
      (let [effective (widen-scope ctx granted)]
        (boolean (get-by-uri ctx uri {:ontology-ids effective}))))))

(def exists-doc
  "PURPOSE — Cheap closed-world existence check: is there a concept at
   this URI in your scoped projection? The ASK-equivalent. Use as a
   guard before a more expensive call (get-concept, neighborhood).

   EXAMPLE
     (exists? \"concept:dir/jane-roe\")  ;; => true
     (exists? \"concept:dir/no-such\")   ;; => false

   RETURNS — boolean (true / false).

   SCOPE — closed-world over your granted ontology section(s). A
   false result means \"not in this scope\" — NOT \"does not exist in
   the world\". Same URI may exist under a different spelling, or in a
   section you weren't granted.")

;; =============================================================================
;; Tool: absent-in-graph?
;; =============================================================================

(defn- make-absent-in-graph-fn
  [{:keys [event-store tenant-id cache] :as cfg}]
  (let [granted (:ontology-ids (resolve-granted-scope cfg))
        get-relationships (ont-fn 'ai.obney.orc.ontology.core.read-models/get-relationships)
        ctx (cond-> {:event-store event-store}
              tenant-id (assoc :tenant-id tenant-id)
              cache     (assoc :cache cache))]
    (fn absent-in-graph?
      ([uri predicate]
       (absent-in-graph? uri predicate nil))
      ([uri predicate target]
       (let [;; Predicates are stored as strings on relationship-created
             ;; events (the ontology convention — :predicate is an "open
             ;; string"; see ontology/commands/create-relationship). Tolerate
             ;; keyword predicates from sandbox callers by coercing to a
             ;; comparable string form. A keyword like :directed and a
             ;; string "directed" both reach the same edge.
             pred-str (if (keyword? predicate) (name predicate) (str predicate))
             same-pred? (fn [stored]
                          (= pred-str
                             (if (keyword? stored) (name stored) (str stored))))
             effective (widen-scope ctx granted)
             all (get-relationships ctx)
             ;; Scoped to widened grant — a relationship belongs to scope
             ;; iff its :ontology-id is in the effective set OR (legacy)
             ;; it has no :ontology-id. Closed-world: we only assert
             ;; absence within what's projected.
             in-scope (filter (fn [r]
                                (let [oid (:ontology-id r)]
                                  (or (nil? oid) (contains? effective oid))))
                              all)
             matches-edge? (fn [r]
                             (and (= uri (:source-uri r))
                                  (same-pred? (:predicate r))
                                  (or (nil? target)
                                      (= target (:target-uri r)))))
             found? (some matches-edge? in-scope)]
         (not (boolean found?)))))))

(def absent-in-graph-doc
  "PURPOSE — Closed-world negation: is there NO edge of the given
   predicate out of `uri` in your scoped graph? Use this instead of
   `(neighborhood ...)` + manual filtering when you only need a
   yes/no answer for the absence. The deterministic SPARQL-NOT-EXISTS
   analogue for the projection.

   Predicates are STRINGS in the projection (e.g. \"directed\",
   \"skos:broader\"); a keyword argument is coerced to the matching
   string via `name`, so both `(... \"directed\")` and `(... :directed)`
   find the same edge.

   EXAMPLE
     ;; Does jane-roe have ANY \"directed\" edge?
     (absent-in-graph? \"concept:dir/jane-roe\" \"directed\")
     ;; => false  (an edge exists)

     ;; Does jane-roe specifically NOT have directed -> red-dawn?
     (absent-in-graph? \"concept:dir/jane-roe\" \"directed\" \"concept:film/red-dawn\")
     ;; => false  (that exact triple exists)

     ;; Does jane-roe have any \"retired\" edge?
     (absent-in-graph? \"concept:dir/jane-roe\" \"retired\")
     ;; => true  (no such edge)

   RETURNS — boolean. true when no matching edge is present in your
   scoped relationships projection.

   SCOPE — closed-world over your granted ontology section(s). A
   true result means \"no such edge in this scope\" — not a global
   claim. An edge whose confidence-class is :ambiguous still COUNTS as
   present (ambiguity is metadata; the edge IS in the graph).")

;; =============================================================================
;; Tool: filter-by-label-pattern
;; =============================================================================

(defn- compile-pattern
  "Build a predicate over label strings from `pattern` and `case-sensitive?`.
   - When pattern is a java.util.regex.Pattern, use re-find as-is.
   - When pattern is a string, do substring containment.
   When case-sensitive? is false (default), the comparison is lowercased
   on both sides."
  [pattern case-sensitive?]
  (cond
    (instance? java.util.regex.Pattern pattern)
    (if case-sensitive?
      (fn [s] (boolean (and s (re-find pattern s))))
      (let [flags-pattern (java.util.regex.Pattern/compile
                            (.pattern pattern)
                            (bit-or (.flags pattern)
                                    java.util.regex.Pattern/CASE_INSENSITIVE))]
        (fn [s] (boolean (and s (re-find flags-pattern s))))))

    (string? pattern)
    (if case-sensitive?
      (fn [s] (boolean (and s (str/includes? s pattern))))
      (let [p-lc (str/lower-case pattern)]
        (fn [s] (boolean (and s (str/includes? (str/lower-case s) p-lc))))))

    :else
    (throw (ex-info (str "filter-by-label-pattern: pattern must be a "
                         "java.util.regex.Pattern or a string. Got: "
                         (pr-str (type pattern)))
                    {:pattern pattern}))))

(defn- concept-labels
  "Collect every label-shaped string from a concept. Returns the primary
   `:label` plus every `:value` from S04 `:labels` (each map or string).
   Does NOT include the URI — the tool name is `filter-by-label-pattern`
   and matching by URI would surprise the model. URI filtering, if it
   becomes useful, would be a separate `filter-by-uri-pattern` tool."
  [c]
  (let [from-labels (->> (:labels c)
                         (keep (fn [l]
                                 (cond
                                   (string? l) l
                                   (map? l)    (:value l)))))]
    (vec (distinct (cond-> from-labels
                     (:label c) (concat [(:label c)]))))))

(defn- make-filter-by-label-pattern-fn
  [{:keys [event-store tenant-id cache] :as cfg}]
  (let [granted (:ontology-ids (resolve-granted-scope cfg))
        get-by-uri (ont-fn 'ai.obney.orc.ontology.interface/get-concept-by-uri)
        ctx (cond-> {:event-store event-store}
              tenant-id (assoc :tenant-id tenant-id)
              cache     (assoc :cache cache))]
    (fn filter-by-label-pattern
      ([uris pattern]
       (filter-by-label-pattern uris pattern {}))
      ([uris pattern opts]
       (let [case-sensitive? (boolean (:case-sensitive? opts))
             matches? (compile-pattern pattern case-sensitive?)
             effective (widen-scope ctx granted)
             concepts (->> (or uris [])
                           (keep (fn [u]
                                   (get-by-uri ctx u
                                               {:ontology-ids effective}))))]
         (vec
          (filter (fn [c]
                    (some matches? (concept-labels c)))
                  concepts)))))))

(def filter-by-label-pattern-doc
  "PURPOSE — Filter a seq of URIs to those whose concept-labels match
   a regex or substring pattern. Use after retrieval when the result
   pool is too broad and you want a deterministic, free name-shaped
   filter without a second LLM call.

   EXAMPLE
     (filter-by-label-pattern
       [\"concept:dir/jane-roe\" \"concept:dir/john-doe\" \"concept:film/red-dawn\"]
       #\"^Jane\")
     ;; => [{:uri \"concept:dir/jane-roe\" :label \"Jane Roe\" ...}]

     ;; Substring (case-insensitive by default):
     (filter-by-label-pattern uris \"dawn\")
     ;; => [{:uri \"concept:film/red-dawn\" :label \"Red Dawn\" ...}]

     ;; Case-sensitive opt:
     (filter-by-label-pattern uris \"Red\" {:case-sensitive? true})

   RETURNS — vector of FULL concept maps (same shape as get-concept)
   for URIs whose labels matched. Empty vector when nothing matches —
   NEVER an exception on a clean miss.

   SCOPE — automatically scoped to your granted ontology section(s).
   URIs in `uris` that resolve to nothing in your scope are silently
   dropped (no error, just absent from results).")

;; =============================================================================
;; Tool: classify-task / classify-behaviors
;; =============================================================================
;; These are thin wrappers around the existing classifier surface. The
;; classifier doesn't yet take :ontology-id scoping (it walks the
;; descriptions index globally — that's its design, not a leak), so
;; the granted scope is informational only here.

(defn- make-classify-task-fn
  [{:keys [event-store tenant-id cache]}]
  (let [classify (ont-fn 'ai.obney.orc.ontology.interface/classify-task)
        ctx (cond-> {:event-store event-store}
              tenant-id (assoc :tenant-id tenant-id)
              cache     (assoc :cache cache))]
    (fn classify-task-tool
      ([opts]
       (classify ctx (or opts {}))))))

(def classify-task-doc
  "PURPOSE — Run task classification against the corpus of recorded
   tree-fingerprint descriptions: which existing tree-class fits this
   task signature, or should a fresh one be minted?

   EXAMPLE
     (classify-task {:task-signature \"summarize a director's filmography\"
                     :threshold 0.65})
     ;; => {:assigned-tree-id #uuid \"...\"
     ;;     :confidence 0.78
     ;;     :top-candidates [{...} {...}]
     ;;     :reasoning \"Matches the summary-by-entity tree class...\"
     ;;     :was-fresh-mint? false}

     ;; With a parent-context summary:
     (classify-task {:task-signature \"...\"
                     :parent-summary \"prior sheet produced a list of directors\"
                     :threshold 0.7})

   RETURNS — {:assigned-tree-id :confidence :top-candidates :reasoning
              :was-fresh-mint?}.")

(defn- make-classify-behaviors-fn
  [{:keys [event-store tenant-id cache]}]
  (let [classify (ont-fn 'ai.obney.orc.ontology.interface/classify-behaviors)
        ctx (cond-> {:event-store event-store}
              tenant-id (assoc :tenant-id tenant-id)
              cache     (assoc :cache cache))]
    (fn classify-behaviors-tool
      ([opts]
       (classify ctx (or opts {}))))))

(def classify-behaviors-doc
  "PURPOSE — Find the top-N behavioral-subtree examples whose recorded
   capabilities match this task signature. Use to discover few-shot
   examples BEFORE you decide reuse / adapt / mint.

   EXAMPLE
     (classify-behaviors {:task-signature \"extract dates from a transcript\"
                          :threshold 0.6
                          :top-n 3})
     ;; => [{:target-id #uuid \"...\" :confidence 0.81
     ;;      :body {:capabilities [...] :strengths [...] ...}
     ;;      :reasoning \"...\"}
     ;;     ...]

   RETURNS — vector of {:target-id :confidence :body :reasoning} maps.
   Empty vector when nothing meets the threshold.")

;; =============================================================================
;; Public binding builder
;; =============================================================================

(defn build-ontology-tool-bindings
  "Return the SCI {symbol -> fn} map for the seven S19 ontology tools.

   cfg keys:
     :event-store              REQUIRED. Grain event-store-v3 handle.
     :granted-ontology-id      EITHER this or :granted-ontology-ids.
     :granted-ontology-ids     Coll of ontology section ids.
     :tenant-id                Optional. Threaded to read-model lookups.
     :cache                    Optional. Threaded to read-model lookups.

   Returns nil when no event-store OR no grant is supplied — the
   sandbox MUST NOT silently expose unscoped tools.

   Each fn carries the same docstring the model sees (the *-doc strings
   above) on its metadata; the docstring-quality test reads it from
   var/fn metadata via attached `^{:doc ...}` on the symbol → fn entry.

   Wiring detail: SCI lets us attach docstring metadata to the symbol
   itself in the bindings map; we put the doc on the var-quoted symbol
   key so the test can read it deterministically via the returned
   map's metadata, AND we attach it on the fn-object via Clojure's
   `with-meta` so introspection from inside the sandbox (`(meta
   graph-search)`) returns the doc too."
  [{:keys [event-store] :as cfg}]
  (when (and event-store (resolve-granted-scope cfg))
    (let [;; Attach the docstring so the sandbox-side (meta f) returns it,
          ;; AND so the docstring-quality test can introspect it through
          ;; the bindings map.
          with-doc (fn [f doc] (with-meta f {:doc doc}))]
      {'graph-search           (with-doc (make-graph-search-fn cfg) graph-search-doc)
       'neighborhood           (with-doc (make-neighborhood-fn cfg) neighborhood-doc)
       'get-concept            (with-doc (make-get-concept-fn cfg) get-concept-doc)
       'exists?                (with-doc (make-exists-fn cfg) exists-doc)
       'absent-in-graph?       (with-doc (make-absent-in-graph-fn cfg) absent-in-graph-doc)
       'filter-by-label-pattern (with-doc (make-filter-by-label-pattern-fn cfg) filter-by-label-pattern-doc)
       'classify-task          (with-doc (make-classify-task-fn cfg) classify-task-doc)
       'classify-behaviors     (with-doc (make-classify-behaviors-fn cfg) classify-behaviors-doc)})))

(def ontology-tool-docs
  "The {symbol -> docstring} map for the S19 builder-facing tool set.

   Exposed so the S20 graph-orientation card and the S18 seed-corpus
   authoring path can pull the same docstrings the sandbox sees,
   without rebuilding bindings (which requires an event-store)."
  {'graph-search            graph-search-doc
   'neighborhood            neighborhood-doc
   'get-concept             get-concept-doc
   'exists?                 exists-doc
   'absent-in-graph?        absent-in-graph-doc
   'filter-by-label-pattern filter-by-label-pattern-doc
   'classify-task           classify-task-doc
   'classify-behaviors      classify-behaviors-doc})
