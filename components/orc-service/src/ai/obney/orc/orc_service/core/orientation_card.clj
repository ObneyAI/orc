(ns ai.obney.orc.orc-service.core.orientation-card
  "S20 — Graph orientation card for the recursive-RLM sandbox.

   When the recursive-RLM sandbox is built with `:granted-ontology-id` (or
   `:granted-ontology-ids`) per S19, the model is granted the seven
   ontology tools. Without an orientation artifact, the model would have
   to explore blind — `graph-search`, `exists?`, and `neighborhood` calls
   thrown at the graph hoping to discover what's in it. This card is the
   graph's equivalent of a large-document preview: rendered ONCE per
   ontology-id (cached, refreshed on reindex), injected into the model's
   prompt right above the tool affordances so the model can form a
   well-grounded first query.

   ## Four deterministic layers (no LLM)

   1. **Identity** — ORSD spec (S14) + ontology metadata (S04) + the
      section + alignment-section registry (S03). Degrades gracefully
      when ORSD spec is absent: still renders metadata + registry with
      an explicit \"treat any query as exploratory\" note.

   2. **T-Box digest** — scopes (concept distribution by `:scope` enum),
      concept families by URI prefix (compensates for the closed-enum
      scope: in a domain graph that uses `:custom` for everything, URI
      prefix carries the semantic 'type'), top classes with instance
      counts (class-URIs identified by URI prefix `class:` OR `:role`
      scope), predicates with usage counts + characteristics (axiom
      data — transitive, functional, inverse-of, sub-property-of), and
      axiom summary (disjoint sets, chain definitions).

   3. **Content sample** — top-N concepts by a hybrid degree+evidence
      score, with one-line descriptions, plus K representative
      neighborhoods (1-hop expansion around the K highest-degree
      non-class concepts). The hybrid algorithm uses RRF
      fusion of degree-rank and evidence-count-rank — robust to scale
      differences and gracefully degenerates to degree-only when
      evidence is sparse (the common case for fresh graphs).

   4. **Tool affordances** — for each of the seven S19 tools, render the
      docstring's PURPOSE + RETURNS sections and a worked one-liner
      using a real URI + predicate from THIS graph. The full docstring
      (including the canonical EXAMPLE block) remains accessible from
      inside the sandbox via `(meta <tool>)`.

   ## Cache + reindex refresh

   The card is cached in a per-process atom, keyed by
   `[ontology-id last-rebuild-timestamp]`. The `last-rebuild-timestamp`
   is read from S09's `:ontology/reindex-state` projection — when a
   `:colbert/index-created` event lands with `:index-name
   \"ontology-descriptions\"`, the timestamp bumps and the cache key
   misses on the next request, forcing recomputation against the
   current projection state. This deliberately mirrors the slice's
   contract: cached on the second request (proves recomputation isn't
   happening) AND refreshed on reindex (proves the cache isn't a stuck
   no-op).

   Card generation is pure-fn over the projection state; nothing is
   stored as events. The cache is an OPTIMIZATION — the card content is
   always derivable from projections.

   ## Sandbox injection (the consumer side)

   The card is included in the recursive-RLM prompt section that lists
   tool affordances. The executor calls `(card-for ctx ontology-id)`
   when `:granted-ontology-id` is present on the node config. Without
   a grant, no card is injected (information-leak safe: no default
   ontology surface).

   ## Read-side only

   Every fn in this namespace projects existing events; none emits
   events. Grain discipline: zero `es/append`, zero `defcommand`,
   zero `process-command`."
  (:require [clojure.string :as str]
            [ai.obney.orc.orc-service.core.sandbox-tools :as sandbox-tools]))

;; =============================================================================
;; Lazy ontology resolution (the orc-service does not depend on ontology;
;; this mirrors the precedent in sandbox-tools/ont-fn).
;; =============================================================================

(defn- ont-fn
  "Resolve an ontology-interface or read-model fn lazily. Throws a clear
   error when ontology isn't on the classpath (test scaffolds without it
   should fail loudly rather than silently no-op)."
  [sym]
  (or (requiring-resolve sym)
      (throw (ex-info (str "S20 orientation-card requires the ontology component "
                           "on the classpath. Could not resolve: " sym)
                      {:symbol sym}))))

;; =============================================================================
;; Helpers — read projections (lazy)
;; =============================================================================

(defn- project*
  [ctx kw]
  (let [project (ont-fn 'ai.obney.grain.read-model-processor-v2.interface/project)]
    (project ctx kw)))

;; =============================================================================
;; Class detection
;; =============================================================================

(defn- class-uri?
  "A concept is treated as a class when its URI starts with `class:`
   (the OWL convention) OR when its `:scope` enum is `:role` (the
   ontology's class-role scope). Domain graphs that opt out of the
   `class:` URI convention can fall back to scope-based detection."
  [c]
  (or (= :role (:scope c))
      (and (:uri c) (str/starts-with? (str (:uri c)) "class:"))))

;; =============================================================================
;; Per-section concept access
;; =============================================================================

(defn- concepts-in-scope
  "Return the seq of concept records for the given ontology-id from the
   per-section projection."
  [ctx ontology-id]
  (vals (get (project* ctx :ontology/concepts-by-section) ontology-id {})))

(defn- relationships-in-scope
  "Return relationships whose `:ontology-id` matches the scope (legacy
   relationships without an `:ontology-id` are included — they're
   global until S02 backfill, and the slice doesn't introduce a
   migration)."
  [ctx ontology-id]
  (filter (fn [r]
            (let [oid (:ontology-id r)]
              (or (nil? oid) (= oid ontology-id))))
          (vals (project* ctx :ontology/relationships))))

;; =============================================================================
;; LAYER 1 — Identity
;; =============================================================================

(defn- render-identity
  "Render the identity layer: ORSD spec (or graceful degradation message),
   ontology metadata, section + alignment registry."
  [ctx ontology-id]
  (let [get-spec       (ont-fn 'ai.obney.orc.ontology.interface/get-ontology-spec)
        get-meta       (ont-fn 'ai.obney.orc.ontology.core.read-models/get-ontology-metadata)
        get-aligns     (ont-fn 'ai.obney.orc.ontology.interface/get-alignment-sections)
        spec   (get-spec ctx ontology-id)
        meta   (get-meta ctx ontology-id)
        aligns (get-aligns ctx ontology-id)
        sb     (StringBuilder.)]
    (.append sb "## IDENTITY\n\n")
    ;; ORSD
    (if spec
      (do
        (.append sb "**ORSD spec.**\n")
        (when-let [p (:purpose spec)]
          (.append sb (str "- Purpose: " p "\n")))
        (when-let [s (:scope spec)]
          (.append sb (str "- Scope: " s "\n")))
        (when-let [uses (seq (:intended-uses spec))]
          (.append sb (str "- Intended uses: " (str/join "; " uses) "\n")))
        (when-let [cqs (seq (:competency-questions spec))]
          (.append sb "- Competency questions this graph commits to answering:\n")
          (doseq [[i q] (map-indexed vector cqs)]
            (.append sb (str "    " (inc i) ". " q "\n"))))
        (when-let [stmts (seq (:natural-language-statements spec))]
          (.append sb "- Natural-language statements:\n")
          (doseq [s stmts]
            (.append sb (str "    - " s "\n"))))
        (.append sb "\n"))
      (.append sb (str "**ORSD spec.** Not recorded for this ontology. The graph "
                       "still has metadata + section + alignment registry below, "
                       "but no formal purpose / scope / competency questions are "
                       "declared. Treat any query as exploratory.\n\n")))
    ;; Metadata
    (if meta
      (do
        (.append sb "**Ontology metadata.**\n")
        (when-let [t (:title meta)]    (.append sb (str "- Title: " t "\n")))
        (when-let [v (:version meta)]  (.append sb (str "- Version: " v "\n")))
        (when-let [l (:license meta)]  (.append sb (str "- License: " l "\n")))
        (when-let [c (:creator meta)]  (.append sb (str "- Creator: " c "\n")))
        (.append sb "\n"))
      (.append sb "**Ontology metadata.** None recorded.\n\n"))
    ;; Section + alignment
    (.append sb (str "**Section + alignment.**\n"
                     "- Granted scope (this card's section): " ontology-id "\n"))
    (if (seq aligns)
      (do
        (.append sb (str "- Registered alignments your queries may auto-widen "
                         "into (" (count aligns) "):\n"))
        (doseq [a aligns]
          (let [am (get-meta ctx a)]
            (.append sb (str "    - " a
                             (when-let [t (:title am)] (str "  (" t ")"))
                             "\n")))))
      (.append sb (str "- No registered alignments. Queries stay within the "
                       "granted section.\n")))
    (.toString sb)))

;; =============================================================================
;; LAYER 2 — T-Box digest
;; =============================================================================

(defn- scope-counts
  [ctx ontology-id]
  (reduce (fn [m c] (update m (or (:scope c) :unscoped) (fnil inc 0)))
          {} (concepts-in-scope ctx ontology-id)))

(defn- uri-prefix-counts
  "Group concepts by their URI prefix (`concept:p` for `concept:p/jane`,
   `class:Director` for `class:Director`). The closed-enum `:scope` is
   limited to a fixed set (failure/success/problem/node-type/custom/
   tree-class/behavioral-subtree) — domain graphs that use `:custom` for
   everything lose semantic 'type' information in the scope dimension.
   The URI prefix carries that information by convention and is the
   single most decision-relevant input for first-query formation."
  [ctx ontology-id]
  (reduce (fn [m c]
            (let [uri (str (:uri c))
                  prefix (let [colon (.indexOf uri ":")]
                           (if (neg? colon)
                             "(no prefix)"
                             (let [after (subs uri (inc colon))
                                   slash (.indexOf after "/")]
                               (str (subs uri 0 colon)
                                    ":"
                                    (if (neg? slash)
                                      after
                                      (subs after 0 slash))))))]
              (update m prefix (fnil inc 0))))
          {} (concepts-in-scope ctx ontology-id)))

(defn- top-classes
  "Return [{:uri :label :instances n} ...] sorted by instance count
   descending. Classes are detected via `class-uri?`. Instances are
   concepts whose `:broader` set contains the class URI."
  [ctx ontology-id]
  (let [concepts (concepts-in-scope ctx ontology-id)
        classes  (filter class-uri? concepts)
        instance-counts
        (reduce (fn [m c]
                  (reduce (fn [mm bu]
                            (update mm bu (fnil inc 0)))
                          m (:broader c)))
                {} concepts)]
    (->> classes
         (map (fn [c]
                {:uri (:uri c)
                 :label (:label c)
                 :instances (get instance-counts (:uri c) 0)}))
         (sort-by :instances >))))

(defn- predicate-summary
  "Return predicates with usage counts, characteristics, inverse-of,
   sub-property-of. Sorted by count descending."
  [ctx ontology-id]
  (let [get-axioms (ont-fn 'ai.obney.orc.ontology.core.read-models/get-axioms)
        rels   (relationships-in-scope ctx ontology-id)
        counts (frequencies (map :predicate rels))
        ax     (or (get-axioms ctx ontology-id) {})
        chars  (:characteristics ax)
        inv    (:inverse-of ax)
        subs   (:sub-property-of ax)]
    (->> counts
         (map (fn [[p n]]
                {:predicate p
                 :count n
                 :characteristics (or (get chars p) #{})
                 :inverse-of (get inv p)
                 :sub-property-of (get subs p)}))
         (sort-by :count >))))

(defn- render-tbox
  [ctx ontology-id]
  (let [get-axioms (ont-fn 'ai.obney.orc.ontology.core.read-models/get-axioms)
        sb (StringBuilder.)
        scopes   (scope-counts ctx ontology-id)
        prefixes (uri-prefix-counts ctx ontology-id)
        classes  (top-classes ctx ontology-id)
        preds    (predicate-summary ctx ontology-id)
        ax       (or (get-axioms ctx ontology-id) {})]
    (.append sb "## T-BOX DIGEST\n\n")
    ;; Scopes (by enum)
    (.append sb "**Scopes (concept distribution by enum).**\n")
    (if (seq scopes)
      (doseq [[s n] (sort-by val > scopes)]
        (.append sb (str "- " s ": " n "\n")))
      (.append sb "- (none)\n"))
    (.append sb "\n")
    ;; Concept families by URI prefix
    (.append sb "**Concept families (by URI prefix).**\n")
    (if (seq prefixes)
      (doseq [[p n] (sort-by val > prefixes)]
        (.append sb (str "- " p ": " n "\n")))
      (.append sb "- (none)\n"))
    (.append sb "\n")
    ;; Top classes
    (.append sb "**Top classes (by direct instance count).**\n")
    (if (seq classes)
      (doseq [{:keys [uri label instances]} (take 10 classes)]
        (.append sb (str "- " uri "  \"" label "\"  (" instances " instances)\n")))
      (.append sb "- (no explicit classes — domain has no `class:`-prefixed URIs and no `:role`-scoped concepts)\n"))
    (.append sb "\n")
    ;; Predicates
    (.append sb "**Predicates (usage + characteristics).**\n")
    (if (seq preds)
      (doseq [{:keys [predicate count characteristics inverse-of sub-property-of]}
              (take 15 preds)]
        (.append sb (str "- " predicate "  (" count " edges)"))
        (when (seq characteristics)
          (.append sb (str "  [" (str/join " " (map name characteristics)) "]")))
        (when inverse-of
          (.append sb (str "  inverse-of=" inverse-of)))
        (when sub-property-of
          (.append sb (str "  sub-of=" sub-property-of)))
        (.append sb "\n"))
      (.append sb "- (no relationships)\n"))
    (.append sb "\n")
    ;; Axiom summary — disjoint sets, sub-property-of hierarchy, chain
    ;; definitions. The :characteristics + :inverse-of axioms are surfaced
    ;; INLINE on each predicate row above so they're visible at a glance;
    ;; the multi-axiom forms (disjoint sets, chains) live here.
    (.append sb "**Axioms.**\n")
    (let [disj   (:disjointness ax)
          subs   (:sub-property-of ax)
          chains (:chains ax)]
      (if (or (seq disj) (seq subs) (seq chains))
        (do
          (when (seq disj)
            (let [full-sets (set (map (fn [[k vs]] (conj vs k)) disj))]
              (.append sb (str "- Disjoint class sets (" (count full-sets) "): "))
              (.append sb (str/join "; "
                                    (for [s full-sets]
                                      (str "{" (str/join ", " (sort s)) "}"))))
              (.append sb "\n")))
          (when (seq subs)
            (.append sb (str "- Sub-property hierarchy (" (count subs) "):\n"))
            (doseq [[sub super] (sort-by key subs)]
              (.append sb (str "    - " sub " ⊑ " super "\n"))))
          (when (seq chains)
            (.append sb "- Chain axioms (derived predicate := chain):\n")
            (doseq [[derived chain] chains]
              (.append sb (str "    - " derived " := " (str/join " ∘ " chain) "\n")))))
        (.append sb "- (no disjointness, sub-property, or chain axioms)\n")))
    (.toString sb)))

;; =============================================================================
;; LAYER 3 — Content sample
;; =============================================================================

(defn- concept-degrees
  "Return {uri -> {:in n :out n}} from in-scope relationships."
  [ctx ontology-id]
  (reduce (fn [m {:keys [source-uri target-uri]}]
            (-> m
                (update-in [source-uri :out] (fnil inc 0))
                (update-in [target-uri :in] (fnil inc 0))))
          {} (relationships-in-scope ctx ontology-id)))

(defn- concept-evidence
  "Return {uri -> evidence-count} for concepts in the granted scope. Uses
   the `:evidence` field on the concept projection (vector of evidence
   maps; we count the vector length)."
  [ctx ontology-id]
  (into {}
        (for [c (concepts-in-scope ctx ontology-id)]
          [(:uri c) (count (or (:evidence c) []))])))

(defn- rrf-rank-table
  "Return a {uri -> rank} map for a sorted-descending seq of [uri score]
   pairs. Zero-indexed (uri ranked 1st has rank 0). Missing uris are
   absent — callers handle that as no-contribution."
  [pairs]
  (into {} (map-indexed (fn [i [uri _]] [uri i]) pairs)))

(defn- top-n-content-sample
  "Hybrid: RRF-fuse degree-rank + evidence-rank with k=60. Returns
   [{:uri :score :degree-rank :evidence-rank} ...] sorted by RRF score.

   This is the algorithm the prototype's cold-read selected: the degree
   signal dominates when evidence is sparse (which is the common case
   for fresh graphs), and the evidence signal corrects for high-degree-
   but-low-evidence concepts when both signals are populated."
  [ctx ontology-id n]
  (let [degs (concept-degrees ctx ontology-id)
        evs  (concept-evidence ctx ontology-id)
        deg-pairs (->> degs
                       (map (fn [[uri {:keys [in out]}]]
                              [uri (+ (or in 0) (or out 0))]))
                       (sort-by second >))
        ev-pairs  (->> evs
                       (filter (fn [[_ v]] (pos? v)))
                       (sort-by second >))
        deg-rank (rrf-rank-table deg-pairs)
        ev-rank  (rrf-rank-table ev-pairs)
        all-uris (set (concat (map first deg-pairs) (map first ev-pairs)))
        k 60]
    (->> all-uris
         (map (fn [u]
                (let [dr (get deg-rank u)
                      er (get ev-rank u)]
                  {:uri u
                   :degree-rank dr
                   :evidence-rank er
                   :score (+ (if dr (/ 1.0 (+ k dr)) 0.0)
                             (if er (/ 1.0 (+ k er)) 0.0))})))
         (sort-by :score >)
         (take n))))

(defn- get-concept
  "Helper — fetch a concept from the per-section projection (avoids the
   URI-keyed last-writer-wins shape on multi-section URIs)."
  [ctx ontology-id uri]
  (get-in (project* ctx :ontology/concepts-by-section) [ontology-id uri]))

(defn- concept-summary-line
  [ctx ontology-id uri]
  (let [c (get-concept ctx ontology-id uri)
        label (or (:label c) "(no label)")
        desc  (or (:description c) "")
        scope (or (:scope c) "?")
        truncated (if (> (count desc) 80)
                    (str (subs desc 0 77) "...")
                    desc)]
    (str "- " uri "  \"" label "\"  [" scope "]"
         (when (not (str/blank? truncated)) (str "  — " truncated)))))

(defn- representative-neighborhoods
  "Pick the K highest-degree non-class concepts and render their 1-hop
   neighborhood (outgoing + incoming edges, capped per direction)."
  [ctx ontology-id k]
  (let [degs   (concept-degrees ctx ontology-id)
        by-uri (into {} (for [c (concepts-in-scope ctx ontology-id)]
                          [(:uri c) c]))
        ranked (->> degs
                    (map (fn [[uri {:keys [in out]}]]
                           [uri (+ (or in 0) (or out 0))]))
                    (filter (fn [[uri _]]
                              (let [c (get by-uri uri)]
                                (and c (not (class-uri? c))))))
                    (sort-by second >)
                    (map first)
                    (take k))
        rels   (relationships-in-scope ctx ontology-id)
        out-of (group-by :source-uri rels)
        in-of  (group-by :target-uri rels)]
    (for [seed ranked]
      {:seed seed
       :label (:label (get by-uri seed))
       :outgoing (take 5 (get out-of seed []))
       :incoming (take 3 (get in-of seed []))})))

(defn- render-content-sample
  [ctx ontology-id {:keys [n-concepts n-neighborhoods]
                    :or {n-concepts 10 n-neighborhoods 3}}]
  (let [sb (StringBuilder.)
        ranked (top-n-content-sample ctx ontology-id n-concepts)
        neighborhoods (representative-neighborhoods ctx ontology-id n-neighborhoods)]
    (.append sb (str "## CONTENT SAMPLE (algorithm: hybrid RRF, degree+evidence)\n\n"
                     "**Top " n-concepts " concepts (by graph degree + evidence count, RRF-fused).**\n"))
    (if (seq ranked)
      (doseq [{:keys [uri]} ranked]
        (.append sb (concept-summary-line ctx ontology-id uri))
        (.append sb "\n"))
      (.append sb "- (no concepts in scope yet — graph is empty or freshly initialized)\n"))
    (.append sb "\n**Representative neighborhoods.** (Use `(neighborhood \"<uri>\")` for full expansion.)\n")
    (if (seq neighborhoods)
      (doseq [{:keys [seed label outgoing incoming]} neighborhoods]
        (.append sb (str "- " seed "  \"" (or label "(no label)") "\"\n"))
        (doseq [{:keys [predicate target-uri]} outgoing]
          (.append sb (str "    → " predicate " → " target-uri "\n")))
        (doseq [{:keys [predicate source-uri]} incoming]
          (.append sb (str "    ← " predicate " ← " source-uri "\n"))))
      (.append sb "- (no edges yet)\n"))
    (.toString sb)))

;; =============================================================================
;; LAYER 4 — Tool affordances
;; =============================================================================

(def ^:private section-marker-re
  "Matches each PURPOSE/EXAMPLE/RETURNS/SCOPE section marker in S19's
   docstrings. PURPOSE/RETURNS/SCOPE use the ` — ` separator; EXAMPLE
   uses a newline + indented code block. Both shapes are matched.
   Non-greedy, anchored to the next marker OR end-of-string."
  #"(?ms)^\s*(PURPOSE|EXAMPLE|RETURNS|SCOPE)(?:\s+—\s+|\s*\n)(.+?)(?=^\s*(?:PURPOSE|EXAMPLE|RETURNS|SCOPE)(?:\s+—\s+|\s*\n)|\z)")

(defn- chunk-docstring
  "Split a tool docstring into {:purpose :example :returns :scope}
   sections. Empty map when no markers are present."
  [doc]
  (when doc
    (let [matches (re-seq section-marker-re doc)]
      (into {} (for [[_ name body] matches]
                 [(keyword (str/lower-case name))
                  (str/trim body)])))))

(defn- pick-example-uri
  "Pick a real URI from the graph (the highest-degree concept) to use as
   the worked example. Returns nil when the graph is empty."
  [ctx ontology-id]
  (let [degs (concept-degrees ctx ontology-id)]
    (->> degs
         (map (fn [[uri {:keys [in out]}]]
                [uri (+ (or in 0) (or out 0))]))
         (sort-by second >)
         ffirst)))

(defn- pick-example-predicate
  "Pick the most-used predicate. Returns nil when the graph has no edges."
  [ctx ontology-id]
  (:predicate (first (predicate-summary ctx ontology-id))))

(defn- worked-example
  [sym ex-uri ex-pred]
  (case sym
    graph-search            (str "(graph-search \""
                                 (or (some-> ex-uri (str/split #"/") last)
                                     "your-query-text")
                                 "\")")
    neighborhood            (str "(neighborhood "
                                 (if ex-uri (str "\"" ex-uri "\"")
                                            "\"concept:your-seed-uri\"")
                                 ")")
    get-concept             (str "(get-concept "
                                 (if ex-uri (str "\"" ex-uri "\"")
                                            "\"concept:your-uri\"")
                                 ")")
    exists?                 (str "(exists? "
                                 (if ex-uri (str "\"" ex-uri "\"")
                                            "\"concept:your-uri\"")
                                 ")")
    absent-in-graph?        (str "(absent-in-graph? "
                                 (if ex-uri (str "\"" ex-uri "\"")
                                            "\"concept:your-uri\"")
                                 " \""
                                 (or ex-pred "your-predicate")
                                 "\")")
    filter-by-label-pattern (str "(filter-by-label-pattern "
                                 (if ex-uri (str "[\"" ex-uri "\"]")
                                            "[\"concept:uri-1\" \"concept:uri-2\"]")
                                 " \"substring\")")
    classify-task           "(classify-task {:task-signature \"<your task signature>\"})"
    classify-behaviors      "(classify-behaviors {:task-signature \"<your task signature>\" :top-n 3})"))

(def ^:private tool-order
  "The eight tools, rendered in the same order as the S19 docstring map."
  ['graph-search 'neighborhood 'get-concept 'exists?
   'absent-in-graph? 'filter-by-label-pattern
   'classify-task 'classify-behaviors])

(defn- render-tool-affordances
  [ctx ontology-id]
  (let [ex-uri  (pick-example-uri ctx ontology-id)
        ex-pred (pick-example-predicate ctx ontology-id)
        docs    sandbox-tools/ontology-tool-docs
        sb      (StringBuilder.)]
    (.append sb "## TOOL AFFORDANCES\n\n")
    (.append sb (str "Eight tools are exposed in this sandbox. Each entry shows "
                     "PURPOSE, RETURNS, and a worked one-liner using a REAL URI "
                     "from THIS graph. The canonical EXAMPLE blocks remain "
                     "accessible from inside the sandbox via `(meta <tool>)`.\n\n"))
    (doseq [sym tool-order]
      (let [doc (get docs sym)
            parts (chunk-docstring doc)]
        (.append sb (str "### `" sym "`\n"))
        (when-let [p (:purpose parts)]
          (.append sb (str "- PURPOSE: "
                           (-> p (str/replace #"\s+" " ") str/trim) "\n")))
        (when-let [r (:returns parts)]
          (.append sb (str "- RETURNS: "
                           (-> r (str/replace #"\s+" " ") str/trim) "\n")))
        (.append sb (str "- EXAMPLE (this graph): `"
                         (worked-example sym ex-uri ex-pred) "`\n"))
        (.append sb "\n")))
    (.toString sb)))

;; =============================================================================
;; The whole card (deterministic, render in order)
;; =============================================================================

(defn render-card
  "Render the full four-layer orientation card for the given ontology-id.

   Options:
   - :n-concepts       — content sample top-N (default 10)
   - :n-neighborhoods  — content sample representative neighborhoods (default 3)

   Returns a string. Pure-fn over the projection state — call this when
   you want a fresh card without cache (the test path)."
  ([ctx ontology-id]
   (render-card ctx ontology-id {}))
  ([ctx ontology-id opts]
   (str "# GRAPH ORIENTATION CARD\n\n"
        "_Section " ontology-id "_\n\n"
        "This card describes the ontology section you have access to via "
        "the seven sandbox tools listed at the bottom. All four layers are "
        "auto-generated from the projection state — they reflect the graph "
        "as of the last reindex. Use the tool affordances to explore.\n\n"
        (render-identity ctx ontology-id) "\n"
        (render-tbox ctx ontology-id) "\n"
        (render-content-sample ctx ontology-id opts) "\n"
        (render-tool-affordances ctx ontology-id))))

;; =============================================================================
;; Cache — keyed by [ontology-id last-rebuild-timestamp]
;; =============================================================================
;;
;; A per-process atom. The cache key reads the current
;; `:last-rebuild-timestamp` from S09's reindex-state projection — when a
;; `:colbert/index-created` event with `:index-name "ontology-descriptions"`
;; lands, the timestamp bumps and the next request misses the cache,
;; forcing a fresh recompute.
;;
;; This is the contract the slice's adversarial test asserts: cached on
;; the second request (no recomputation), AND refreshed on reindex (no
;; stuck-no-op). Both halves are independently testable through
;; `card-for` (the public entry point).

(defonce ^:private cache
  (atom {}))

(defn- reindex-fingerprint
  "Return the cache fingerprint for an ontology-id. We use the
   `:last-rebuild-timestamp` from the reindex-state projection — when a
   reindex fires, the timestamp changes and the cache key misses.
   When no reindex has occurred yet, the fingerprint is `:no-reindex-yet`
   — a stable string so the first computation can still cache, and the
   first reindex naturally invalidates it."
  [ctx]
  (let [get-state (ont-fn 'ai.obney.orc.ontology.core.read-models/get-reindex-state)
        s (get-state ctx)]
    (or (:last-rebuild-timestamp s) :no-reindex-yet)))

(defn invalidate!
  "Drop a single ontology-id's cache entry. The reindex-state fingerprint
   normally handles refresh automatically; this fn exists for test
   scaffolding (tests that want to assert recomputation explicitly) and
   for consumers that need to force a refresh outside the reindex path."
  ([ontology-id]
   (swap! cache dissoc ontology-id))
  ([]
   (reset! cache {})))

(defn card-for
  "Return the orientation card for `ontology-id`, recomputing only when
   the reindex fingerprint changed. The cache is a per-process atom and
   safe for concurrent reads.

   When `ontology-id` is nil, returns nil — no information leak (this is
   the path the sandbox takes when no grant is supplied).

   Optional `opts` map (forwarded to `render-card`):
   - :n-concepts       — content sample top-N (default 10)
   - :n-neighborhoods  — representative neighborhoods (default 3)"
  ([ctx ontology-id]
   (card-for ctx ontology-id {}))
  ([ctx ontology-id opts]
   (when ontology-id
     (let [fp (reindex-fingerprint ctx)
           cached (get @cache ontology-id)]
       (if (and cached (= fp (:fingerprint cached)))
         (:card cached)
         (let [card (render-card ctx ontology-id opts)]
           (swap! cache assoc ontology-id
                  {:card card :fingerprint fp})
           card))))))
