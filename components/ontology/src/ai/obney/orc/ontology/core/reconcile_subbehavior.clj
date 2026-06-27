(ns ai.obney.orc.ontology.core.reconcile-subbehavior
  "EB5 — the RECONCILE subbehavior as a delegatable ORC sheet.

   The FOURTH real subbehavior on the EB1 registry/delegation pattern (after EB2
   Survey + EB3 Model + EB4 Extract). A subbehavior is a first-class composed ORC
   sheet, built via the DSL + `build-workflow!`, registered under a stable name →
   deterministic sheet-id, and invoked from a central evolver tree via `:delegate`
   (child tick, isolated blackboard, mapped `:reads`/`:writes`).

   ## What Reconcile does (its ONE job)

   Take EB4's per-source DRAFT SET (`:concept-drafts`/`:relationship-drafts`, the
   `compile-discovery-source!` draft shape) + the granted `:ontology-id` (the
   CURRENT-graph scope) and link the new drafts across sources AND against the
   CURRENT graph state, at TWO granularities — ENTITIES and their ATTRIBUTES/
   FEATURES — with CHECK-BEFORE-MINT (probe the existing graph for a match BEFORE
   landing a draft, so identity is evidence-grounded, not label-cosine). It
   re-houses DT7's `reconcile-graph!` (the against-graph-state pass) as its
   entity-reconcile step and ADDS the attribute/feature-granularity logic + the
   check-before-mint probe.

   ## A FOUR-node sheet: all `:code` (NOT an `:llm` node) — reconcile is
   DETERMINISTIC

   Reconcile is largely DETERMINISTIC: the probe is `hybrid-search` (P3 graph BFS
   + embedding + ColBERT via RRF — evidence, not LLM label-cosine), the landing is
   `compile-discovery-source!` (always-on V18), the entity-reconcile is the reused
   `reconcile-graph!` (S03 alignment + S12 dedup cascade + V18 integrity, with
   `:llm-budget` DEFAULT 0 — ambiguities surface as `:requires-review`, never
   silently merged), and the attribute-linker is structural (the SAME jaro-winkler
   structural-similarity primitive S12 uses — no LLM, no hardcoded phrase list).
   So the body is FOUR `:code` nodes, NO `:llm` node:

     1. `:code` PROBE  — check-before-mint: for each incoming concept-draft, probe
        the CURRENT graph via `hybrid-search` for an existing match BEFORE it is
        landed. The probe INFORMS the mint/merge decision (evidence-grounded, S13)
        — it is not only a downstream dedup. Native return.
     2. `:code` LAND   — land the drafts via the reused `compile-discovery-source!`
        (NO fork): emits `:ontology/create-concept`/`:ontology/create-relationship`
        commands, with the always-on V18 referential-integrity invariant. Events
        LAND here; the reconcile reads them back from the projection. Native return.
     3. `:code` ENTITY-RECONCILE — the reused DT7 `reconcile-graph!` (NO fork):
        reads the CURRENT graph for `:ontology-id` (NOT empty), links entities
        cross-source (shared-URI collapse + S12 LSH near-match cascade), reuses S03
        + S12 + V18, `:llm-budget` 0 (deterministic; ambiguity-band →
        `:requires-review`). Native return.
     4. `:code` ATTRIBUTE-RECONCILE — DEEPENING 2 (the genuinely-new EB5 logic):
        connect a newly-landed entity's ATTRIBUTES/FEATURES to existing entities'
        attributes/features. Beyond `reconcile-graph!`'s entity-level links — it
        relates concept attributes (the `[:map-of :keyword :any]` carried on each
        concept) across the graph by structural key/value match. Native return.

   The 5th step (assemble the public report) is the APPLY `:code` node's own
   return — the report map combines the probe + entity-reconcile + attribute-
   reconcile signals. It is a `:code`-node output, so it crosses `:delegate` PARSED
   (the C1 `:llm` JSON-string failure mode is node-type-specific to the AI
   executor's schema-coercion path, which a `:code` write does not traverse). The
   blackboard still declares a STRUCTURED schema for the report (the EB2/EB3/EB4
   defense-in-depth).

   ## Re-orchestration, not rewrite (8) + domain-agnostic (12)

   No fork: `compile-discovery-source!` (landing + V18), `reconcile-graph!` (S03 +
   S12 + V18 entity-reconcile), `hybrid-search` (P3 probe), and S12's jaro-winkler
   (the attribute-key structural match) are all REUSED. The only EB5 additions are
   the thin `:code` wrappers (so the reused fns slot into the code-node `:fn`
   calling convention), the check-before-mint orchestration, and the attribute-
   granularity linker — all structural / evidence-grounded, NO vertical knowledge,
   NO hardcoded phrase matching (#7/#12)."
  (:require [ai.obney.orc.orc-service.interface :as dsl]
            [ai.obney.orc.ontology.interface :as ontology]
            [ai.obney.orc.ontology.core.discovery-tree :as dt]
            [ai.obney.orc.ontology.core.read-models :as rm]
            [ai.obney.orc.ontology.core.dedup-cascade :as dedup]
            [clojure.set]))

;; =============================================================================
;; The reconcile contract — the public OUTPUT (the reconcile REPORT)
;; =============================================================================

(def reconcile-report-key
  "The Reconcile subbehavior's public OUTPUT contract: a single REPORT map. It
   combines (a) the check-before-mint probe (which drafts matched an existing
   node BEFORE landing), (b) the DT7 entity-reconcile result (shared-URI links,
   near-match merges, `:requires-review` ambiguities, 0-dangling integrity), and
   (c) the attribute/feature-level links the EB5 deepening discovered. It is
   produced by a `:code` node → it crosses `:delegate` PARSED."
  :reconcile-report)

(def reconcile-report-schema
  "STRUCTURED Malli schema for the `:reconcile-report` write. A concrete
   `[:map …]` (NOT a bare `:map`/`:any`) documents the report shape and keeps the
   contract robust across `:delegate` (the EB2/EB3/EB4 defense-in-depth — for a
   `:code` node the natural parse already crosses the seam, but the structured
   schema documents the contract + stays robust if a later AI path routes it).
   `{:closed false}` + `:any` leaf values tolerate the reused fns' rich return
   shapes (the entity-reconcile result, the probe entries, the attribute links)."
  [:map {:closed false}
   [:ontology-id {:optional true} :any]
   [:mint-probe {:optional true} :any]
   [:entity-reconcile {:optional true} :any]
   [:attribute-reconcile {:optional true} :any]
   [:landed {:optional true} :any]
   [:dangling-edge-count {:optional true} :any]
   [:ambiguities-surfaced {:optional true} :any]])

;; =============================================================================
;; DEEPENING 1 — CHECK-BEFORE-MINT: probe the CURRENT graph for a match BEFORE a
;; draft is landed (REUSE `hybrid-search` — P3 BFS+embedding+ColBERT; no fork)
;; =============================================================================

(def ^:private probe-min-similarity
  "The probe's embedding-signal floor. The probe is a check-before-mint SIGNAL,
   not the mint decision itself (the deterministic S12 cascade still adjudicates
   identity after landing) — so the floor is permissive enough to surface a
   plausible existing match for the model/caller to weigh. Reused default."
  0.3)

(def default-max-probe
  "GC-7 — the default ceiling on how many drafts get the FULL P3 `hybrid-search`
   probe (graph BFS + embedding + ColBERT) in one reconcile call. The probe is one
   hybrid-search PER draft; on a comprehensive build (tens of thousands of drafts
   per batch) that is tens of thousands of searches and the build never finishes
   (root-caused in GC-4). This bounds the search WORK.

   Crucially this does NOT weaken identity: the strongest check-before-mint signal
   — `:exact-uri?` (the draft's URI already resolves in the REAL pre-existing
   graph, a reconcile-not-duplicate) — is a free set lookup and is computed for
   EVERY draft regardless of the cap; AND the deterministic S12 dedup cascade still
   adjudicates identity AFTER landing for all drafts. Only the pre-mint
   embedding/ColBERT SIGNAL is bounded, and the reduction is reported honestly via
   `:probe-coverage` (never a silent skip — Discipline 5). A caller raises it with
   `:max-probe`."
  2000)

(defn check-before-mint-probe
  "DEEPENING 1 — for each incoming concept-draft, probe the CURRENT graph via the
   reused P3 `hybrid-search` (graph BFS + embedding + ColBERT via RRF) for an
   EXISTING match BEFORE the draft is landed. This is the check-before-mint seam:
   identity is grounded in graph EVIDENCE (an existing node the probe surfaces),
   not in a label-cosine guess.

   The probe runs BEFORE landing (the caller calls it with the still-unlanded
   drafts), so a hit means 'an EXISTING node the graph already holds resembles
   this draft' — the mint becomes a reconcile-into-existing rather than a fresh
   node. The probe is scoped to `:ontology-id` (S02; S03 alignment auto-widening
   on by default) so it sees the current graph + its alignment sections.

   Query by the draft's own CONTENT (`:label` + `:description`) — the model's own
   words — NOT by seeding the graph BFS on the draft's own `:uri`. Seeding on the
   draft's own URI is circular: the BFS activation echoes the seed back, so an
   unlanded draft would falsely 'match itself'. The CONTENT signals (embedding /
   ColBERT / lexical) find genuinely-EXISTING nodes the draft is plausibly the
   same as. The draft's own URI is filtered out of the hit list (an echo is not
   evidence of a pre-existing match). Domain-agnostic (#12): names no domain. No
   hardcoded phrase matching (#7) — the match is the P3 retrieval signal, not a
   string-equality table.

   `:exact-uri?` (the strongest case — the draft's URI ALREADY resolves in the
   graph, so landing it is a projection no-op, reconcile-not-duplicate) is judged
   against the REAL pre-existing graph (`pre-existing-uris`, read by the caller
   BEFORE landing), NOT against the BFS echo. When the caller omits
   `pre-existing-uris`, it is read here.

   Optional `:signals` restricts the P3 signal set passed to `hybrid-search`
   (default = all three: graph BFS + embedding + ColBERT). Production leaves it
   nil for full evidence; a hermetic fast-gate caller passes #{:graph :lexical} to
   stay off the embedding model / ColBERT bridge.

   Returns:
     {:probed <int>                       ; how many drafts were probed
      :hits   <int>                        ; how many surfaced an existing match
      :exact-uri-hits <int>                ; how many drafts already resolve
      :entries [{:uri :label
                 :existing-uri :existing-label :score
                 :match?    <bool>         ; an OTHER existing node was surfaced
                 :exact-uri? <bool>         ; draft URI already in the graph
                 :hybrid-probed? <bool>}]   ; the full P3 search ran (vs cap-skipped)
      :probe-coverage {...}}                 ; GC-7 honest coverage report

   GC-7 — the per-draft `hybrid-search` is bounded by `:max-probe` (default
   `default-max-probe`): at most that many drafts get the full P3 search. The
   strongest signal — `:exact-uri?` (the draft already resolves in the REAL graph)
   — is a free set lookup and is computed for EVERY draft regardless of the cap, so
   the reconcile-not-duplicate seam is never lost; and the deterministic S12 cascade
   still adjudicates identity after landing for all drafts. Drafts beyond the cap
   carry `:hybrid-probed? false` (their embedding/ColBERT signal was not run) and the
   reduction is reported honestly in `:probe-coverage` (no silent skip — Discipline
   5). `:max-probe` nil/`:all` disables the cap (the original full-probe behavior)."
  [ctx {:keys [ontology-id concept-drafts pre-existing-uris signals colbert-index-id
               max-probe]
        :or {max-probe default-max-probe}
        :as _params}]
  (when-not ontology-id
    (throw (ex-info "check-before-mint-probe requires :ontology-id (the granted scope)"
                    {:ontology-id ontology-id})))
  (let [pre-existing (set (or pre-existing-uris
                              (map :uri (rm/get-concepts ctx {:ontology-id ontology-id}))))
        ;; default the ColBERT index to the ontology's registered index so the P3
        ;; ColBERT signal can fire when the bridge is up (the caller may override).
        colbert-index-id (or colbert-index-id
                             (:colbert-index-id
                              (ontology/get-colbert-index-for-ontology ctx ontology-id)))
        drafts (vec (or concept-drafts []))
        ;; GC-7 — the hybrid-search cap. nil / :all disables it (full-probe). The
        ;; FIRST `cap` drafts get the full P3 search; the rest get only the free
        ;; exact-uri? lookup (honest coverage reported below).
        cap (when (and (number? max-probe) (nat-int? max-probe)) max-probe)
        entries
        (vec
         (map-indexed
          (fn [idx {:keys [uri label description] :as _draft}]
            (let [run-hybrid? (or (nil? cap) (< idx cap))
                  hits (when run-hybrid?
                         (let [res (ontology/hybrid-search
                                    ctx (cond-> {:query-text (str (or label "") " " (or description ""))
                                                 :ontology-id ontology-id
                                                 :limit 5
                                                 :min-similarity probe-min-similarity}
                                          ;; `signals` lets the caller restrict the probe's
                                          ;; signal set (default = all three P3 signals). A
                                          ;; hermetic gate can pass #{:graph :lexical} to stay off
                                          ;; the embedding model + ColBERT bridge; production
                                          ;; leaves it nil for full P3 evidence.
                                          signals (assoc :signals signals)
                                          ;; the ColBERT signal needs the per-ontology index id;
                                          ;; supply it so P3's ColBERT late-interaction fires.
                                          colbert-index-id (assoc :colbert-index-id colbert-index-id)))]
                           ;; drop the draft's OWN uri — an echo of itself is not
                           ;; evidence of a pre-existing match.
                           (remove #(= uri (:uri %)) (:results res))))
                  best (first hits)]
              {:uri uri
               :label label
               :existing-uri (:uri best)
               :existing-label (:label best)
               :score (:score best)
               :match? (boolean (seq hits))
               :hybrid-probed? run-hybrid?
               ;; the draft's URI already resolves in the REAL pre-existing graph
               :exact-uri? (boolean (contains? pre-existing uri))}))
          drafts))
        hybrid-probed (count (filter :hybrid-probed? entries))]
    {:probed (count entries)
     :hits (count (filter :match? entries))
     :exact-uri-hits (count (filter :exact-uri? entries))
     :entries entries
     ;; GC-7 — honest coverage report (never a silent skip). When the cap bit, the
     ;; caller can see exactly how many drafts got the full P3 signal vs only the
     ;; free exact-uri? lookup.
     :probe-coverage {:total (count entries)
                      :hybrid-probed hybrid-probed
                      :hybrid-skipped (- (count entries) hybrid-probed)
                      :max-probe cap
                      :full-coverage? (= hybrid-probed (count entries))}}))

;; =============================================================================
;; DEEPENING 2 — ATTRIBUTE/FEATURE GRANULARITY: connect a NEW entity's attributes
;; to EXISTING entities' attributes (the genuinely-new EB5 logic, beyond
;; reconcile-graph!'s entity-level links)
;; =============================================================================

(def ^:private attr-key-similarity-floor
  "The structural-similarity floor for matching one concept's attribute KEY to
   another concept's attribute key. Reuses the SAME jaro-winkler primitive S12's
   dedup cascade uses for label similarity (no forked similarity notion). A key
   pair at/above this floor is a candidate attribute-level link (e.g. `:region`
   ↔ `:region`, or `:net-cost` ↔ `:netcost`); below it, no link. Deterministic,
   domain-agnostic — names no attribute."
  0.92)

(defn- attr-key-str [k] (if (keyword? k) (name k) (str k)))

(defn attribute-links
  "DEEPENING 2 — the genuinely-new EB5 logic. Connect a NEWLY-landed entity's
   ATTRIBUTES/FEATURES to EXISTING entities' attributes/features.

   `reconcile-graph!` links at the ENTITY level (two nodes are the same thing).
   EB5 adds the ATTRIBUTE level: a new entity carries attributes (the
   `[:map-of :keyword :any]` on each concept — `:region`, `:weight`, `:tier`,
   …); an attribute it carries may already be carried by a DIFFERENT existing
   entity. That is an attribute-level relationship the entity-level pass cannot
   see. This finds those links.

   For each NEW concept (`new-uris`), for each of its attribute key/value pairs,
   scan the EXISTING concepts (those NOT in `new-uris`) for an attribute that
   matches:
     - SAME-VALUE link: the existing entity carries an attribute whose KEY is the
       same (or structurally near, jaro-winkler ≥ floor — the SAME S12 primitive,
       no forked similarity) AND whose VALUE is equal. The strongest signal — two
       entities share a feature value.
     - SHARED-KEY link: the existing entity carries an attribute with a matching
       KEY but a DIFFERENT value (a shared FEATURE dimension, differing value).

   Domain-agnostic (#12): it names no attribute key — it relates whatever
   attributes the drafts carried. Deterministic (#7): structural key match +
   value equality, NO LLM, NO hardcoded phrase list. Self/own-entity pairs are
   excluded (an entity is not linked to itself).

   ## GC-7 — BOUNDED work, IDENTICAL output (scaling fix, behavior-preserving)

   The naive form scanned EVERY new attribute against EVERY existing attribute,
   calling jaro-winkler per pair — `O(new-attrs × existing-attrs)` jaro-winkler
   comparisons. On a comprehensive build (tens of thousands of concepts × several
   attributes) that is billions of comparisons; the comprehensive build never
   finished (root-caused in GC-4).

   The fix is KEY-PAIR MEMOIZATION, NOT a blocking heuristic: the jaro-winkler
   key-similarity is a function of the KEY STRINGS ALONE (`nk-str` vs `key-str`),
   so it is computed ONCE per DISTINCT (new-key-string, existing-key-string) pair
   — at most `distinct-new-keys × distinct-existing-keys`, i.e. schema-width², a
   few hundred, independent of how many CONCEPTS carry those keys. The link set is
   then expanded by pure hash-bucket iteration over the existing attributes grouped
   by key string. Because the FULL key-pair similarity matrix is still evaluated
   (no candidate is pruned by a length/prefix heuristic), the emitted `:links` are
   PROVABLY identical to the naive cross-product, not merely empirically close. The
   reused S12 `jaro-winkler-similarity` + `attr-key-str` are unchanged (no fork).
   `:jw-comparisons` is reported so the bound is observable + guardable.

   Returns:
     {:new-entities-with-attrs <int>
      :links [{:new-uri :new-attr-key :existing-uri :existing-attr-key
               :value :kind (:same-value | :shared-key)}]
      :same-value-link-count <int>
      :shared-key-link-count <int>
      :jw-comparisons <int>}"     ; GC-7 work metric (distinct key-pairs compared)
  [concepts new-uris]
  (let [new-set (set new-uris)
        existing (remove #(contains? new-set (:uri %)) concepts)
        new-concepts (filter #(contains? new-set (:uri %)) concepts)
        ;; index existing attributes for the scan: [{:uri :key :key-str :value}]
        existing-attrs
        (vec (for [c existing
                   [k v] (:attributes c)]
               {:uri (:uri c) :key k :key-str (attr-key-str k) :value v}))
        ;; GC-7 — group existing attributes by their NORMALIZED key string. A key
        ;; string maps to the (possibly many) existing attribute entries carrying
        ;; it; the jaro-winkler near-match is computed against the DISTINCT key
        ;; strings (the bucket keys), never per attribute occurrence.
        existing-by-key (group-by :key-str existing-attrs)
        existing-key-strs (vec (keys existing-by-key))
        ;; GC-7 — memoize, per DISTINCT new key string, the existing key strings
        ;; whose jaro-winkler ≥ floor. This is the ONLY place jaro-winkler runs:
        ;; once per (distinct-new-key × distinct-existing-key) pair. `jw-count`
        ;; tallies the real comparison work (the GC-7 bound metric).
        jw-count (volatile! 0)
        match-cache (volatile! {})
        matching-existing-keys
        (fn [nk-str]
          (if-let [hit (find @match-cache nk-str)]
            (val hit)
            (let [ms (filterv
                      (fn [eks]
                        (vswap! jw-count inc)
                        ;; structural key match — reuse S12's jaro-winkler (no fork)
                        (>= (dedup/jaro-winkler-similarity nk-str eks)
                            attr-key-similarity-floor))
                      existing-key-strs)]
              (vswap! match-cache assoc nk-str ms)
              ms)))
        links
        (vec
         (for [nc new-concepts
               [nk nv] (:attributes nc)
               :let [nk-str (attr-key-str nk)]
               eks (matching-existing-keys nk-str)
               ea (get existing-by-key eks)
               :when (not= (:uri nc) (:uri ea))
               :let [same-value? (= nv (:value ea))]]
           {:new-uri (:uri nc)
            :new-attr-key nk
            :existing-uri (:uri ea)
            :existing-attr-key (:key ea)
            :value (when same-value? nv)
            :kind (if same-value? :same-value :shared-key)}))]
    {:new-entities-with-attrs (count (filter #(seq (:attributes %)) new-concepts))
     :links links
     :same-value-link-count (count (filter #(= :same-value (:kind %)) links))
     :shared-key-link-count (count (filter #(= :shared-key (:kind %)) links))
     :jw-comparisons @jw-count}))

;; =============================================================================
;; The orchestrating reconcile — land drafts, entity-reconcile (DT7), attribute-
;; reconcile, assemble the report. Reused by the sheet's `:code` apply node AND
;; the prototype/live verify.
;; =============================================================================

(defn reconcile-drafts!
  "Orchestrate the full reconcile against the CURRENT graph state (NO fork of the
   reused machinery). Given the per-source draft set + the granted `:ontology-id`:

     1. PROBE  — check-before-mint (`check-before-mint-probe`): probe the CURRENT
        graph for each draft BEFORE landing (evidence-grounded identity).
     2. LAND   — `compile-discovery-source!` (reused): emit the create-concept /
        create-relationship commands + the always-on V18 invariant. Events LAND.
     3. ENTITY — `reconcile-graph!` (reused DT7): read the CURRENT graph for
        `:ontology-id`, link entities cross-source, S03 + S12 + V18, `:llm-budget`
        0 (ambiguities → `:requires-review`, never silently merged).
     4. ATTRS  — `attribute-links` (the EB5 deepening): connect the newly-landed
        entities' attributes to existing entities' attributes.

   Required: `:ontology-id`, `:concept-drafts`.
   Optional: `:relationship-drafts`, `:source-uri-sets` (the DT7 shared-URI
   report), `:llm-budget` (DEFAULT 0 — deterministic), `:llm-fn`, `:probe-signals`
   (restrict the check-before-mint probe's P3 signal set — nil = all three;
   #{:graph :lexical} keeps a hermetic gate off the embedding model / ColBERT).

   The probe runs over the UNLANDED drafts (so a hit means already-present); the
   attribute-link pass runs over the LANDED graph (so the new entities' attributes
   are compared against the existing graph). Returns the public reconcile report."
  [ctx {:keys [ontology-id concept-drafts relationship-drafts source-uri-sets
               llm-budget llm-fn probe-signals max-probe]
        :or {llm-budget 0}
        :as _params}]
  (when-not ontology-id
    (throw (ex-info "reconcile-drafts! requires :ontology-id (the granted scope)"
                    {:ontology-id ontology-id})))
  (let [concept-drafts (vec (or concept-drafts []))
        relationship-drafts (vec (or relationship-drafts []))

        ;; The URIs ALREADY in the graph BEFORE this batch lands — read FIRST so a
        ;; draft that re-mints an EXISTING URI (a cross-source collapse) is NOT
        ;; mis-counted as a brand-new entity. A draft whose URI is NOT pre-existing
        ;; is genuinely new; the attribute pass links its attributes against the
        ;; pre-existing graph (NOT against its sibling new drafts).
        pre-existing-uris (set (map :uri (rm/get-concepts ctx {:ontology-id ontology-id})))
        draft-uris (set (keep :uri concept-drafts))
        new-uris (clojure.set/difference draft-uris pre-existing-uris)

        ;; 1. PROBE the CURRENT graph BEFORE landing (check-before-mint). Pass the
        ;;    pre-existing URI set we just read so :exact-uri? is grounded in the
        ;;    REAL graph (not a BFS echo).
        probe (check-before-mint-probe
               ctx (cond-> {:ontology-id ontology-id
                            :concept-drafts concept-drafts
                            :pre-existing-uris pre-existing-uris
                            :signals probe-signals}
                     ;; GC-7 — bound the per-draft hybrid-search at scale (honest
                     ;; coverage reported in :probe-coverage). nil → the probe's
                     ;; own default cap applies.
                     (contains? _params :max-probe) (assoc :max-probe max-probe)))

        ;; 2. LAND the drafts (REUSE compile-discovery-source! — no fork). It
        ;;    validates the drafts, emits the create commands, runs the always-on
        ;;    V18 invariant, and returns the provenance/integrity report.
        landed (ontology/compile-discovery-source!
                ctx ontology-id
                {:status :emitted-drafts
                 :emitted-concepts concept-drafts
                 :emitted-relationships relationship-drafts})

        ;; 3. ENTITY-reconcile against the CURRENT graph (REUSE reconcile-graph! —
        ;;    no fork). Reads the now-landed graph (NOT empty), links entities
        ;;    cross-source, S03 + S12 + V18, ambiguities → :requires-review.
        entity (dt/reconcile-graph!
                ctx (cond-> {:ontology-id ontology-id
                             :source-uri-sets source-uri-sets
                             :llm-budget llm-budget}
                      llm-fn (assoc :llm-fn llm-fn)))

        ;; 4. ATTRIBUTE-reconcile (the EB5 deepening). Read the LANDED graph and
        ;;    link the NEW entities' attributes to existing entities' attributes.
        concepts (rm/get-concepts ctx {:ontology-id ontology-id})
        attrs (attribute-links concepts new-uris)]
    {:ontology-id ontology-id
     :mint-probe probe
     :landed (:discovery-provenance landed)
     :entity-reconcile entity
     :attribute-reconcile attrs
     ;; surfaced top-level for an easy caller gate (the no-false-green signals)
     :dangling-edge-count (get-in entity [:referential-integrity :dangling-edge-count])
     :ambiguities-surfaced (:ambiguities-surfaced entity)}))

;; =============================================================================
;; The `:code` node wrappers (so the orchestration slots into the code-node
;; `:fn` calling convention) + the delegatable sheet
;; =============================================================================

(defn reconcile-code
  "The APPLY `:code` `:fn`. The orc-service `:code` executor calls the `:fn` with
   `(assoc context :inputs <reads-map> :execution-context context)` — so the ctx
   (event-store, cache, registries, …) IS the top-level arg map, and the node's
   `:reads` arrive under `:inputs`. Runs the full `reconcile-drafts!` orchestration
   (probe → land → entity-reconcile → attribute-reconcile) against that ctx and
   writes the public `:reconcile-report` (native Clojure — crosses `:delegate`
   parsed)."
  [{:keys [inputs] :as ctx}]
  (let [{:keys [ontology-id concept-drafts relationship-drafts source-uri-sets
                llm-budget]} inputs]
    {reconcile-report-key
     (reconcile-drafts!
      ;; ctx is the top-level arg (sans the :inputs/:execution-context envelope
      ;; keys the executor added) — it carries :event-store/:cache/registries.
      (dissoc ctx :inputs :execution-context)
      {:ontology-id ontology-id
       :concept-drafts concept-drafts
       :relationship-drafts relationship-drafts
       :source-uri-sets source-uri-sets
       :llm-budget (or llm-budget 0)})}))

(defn reconcile-subbehavior-name
  "Canonical registry name for the Reconcile subbehavior. Like EB3 Model + EB4
   Extract (and UNLIKE per-source Survey), it bakes in NO source path — it
   reconciles the DRAFT SET it is handed against the CURRENT graph for the
   `:ontology-id` it is handed (both `:reads` inputs), so a SINGLE Reconcile sheet
   serves every source and graph. `\"<family>/<behavior>@v<N>\"` — version is part
   of identity (a new version is a new, separately-evolvable sheet)."
  []
  "ontology-reconcile/reconcile@v1")

(defn reconcile-sheet-id-for
  "Look up the deterministic sheet-id for the Reconcile subbehavior (pure — no
   event-store read). The central tree points its `:delegate` `:target-sheet-id`
   here without rebuilding the subbehavior."
  []
  (dsl/sheet-id-for-name (reconcile-subbehavior-name)))

(defn reconcile-subbehavior-def
  "The Reconcile subbehavior workflow definition.

   Body: a single `:code` node — the deterministic reconcile orchestration (probe
   → land → entity-reconcile → attribute-reconcile). NO `:llm` node (reconcile is
   deterministic; the probe is P3 retrieval evidence, the merges are S12 evidence,
   the attribute links are structural — no label-cosine adjudication).

   Contract (the public `:reads`/`:writes`):
     :reads  [:ontology-id :concept-drafts :relationship-drafts :source-uri-sets]
     :writes [:reconcile-report]
   The report is a `:code`-node output → it crosses `:delegate` PARSED; the
   blackboard declares a STRUCTURED schema for it (defense-in-depth)."
  [{:keys [_model]}]
  (let [nm (reconcile-subbehavior-name)]
    (dsl/workflow nm
      (dsl/blackboard {;; public :reads — the EB4 draft set + the current-graph scope
                       :ontology-id :any
                       :concept-drafts [:vector [:map {:closed false}]]
                       :relationship-drafts [:vector [:map {:closed false}]]
                       :source-uri-sets [:maybe [:vector [:map {:closed false}]]]
                       ;; public :write — the reconcile report
                       reconcile-report-key reconcile-report-schema})
      (dsl/sequence "reconcile-root"
        (dsl/code "reconcile"
          :fn "ai.obney.orc.ontology.core.reconcile-subbehavior/reconcile-code"
          :reads [:ontology-id :concept-drafts :relationship-drafts :source-uri-sets]
          :writes [reconcile-report-key])))))

(defn register-reconcile-subbehavior!
  "REGISTER (build, idempotent) the Reconcile subbehavior sheet and return its
   deterministic sheet-id. Re-registering an unchanged def is a no-op (same id).
   The central evolver tree resolves the name → id via `reconcile-sheet-id-for`
   and `:delegate`s to it."
  [ctx {:keys [model]}]
  (dsl/build-workflow! ctx (reconcile-subbehavior-def {:_model model})))
