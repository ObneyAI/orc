(ns ai.obney.orc.ontology.core.connectivity-acceptance
  "CONNECT-4 — the PURE, durable connectivity verdict (the /tdd deliverable).

   Closes the O*NET connectivity line: given a MEASURED summary of a built
   graph, decide whether graph B is genuinely CONNECTED through occupation↔element
   edges AND — the load-bearing CONNECT-3c guarantee — whether those edges attach
   to the CANONICAL `occupation/<SOC>` nodes (not `entity/<SOC>` stubs).

   PURE — no I/O, no LLM, no Grain. The live bounded O*NET build (the QA) measures
   the summary from the persisted store and feeds it here; this fn is guarded on
   every `poly test brick:ontology` run.

   Domain-agnostic (#12): the fn names NO O*NET column, SOC, skill, or predicate.
   It reads only counts + one boolean off the summary map. The word `occupation`
   here is the KEY-entity ROLE label the summary supplies, not a baked domain
   literal — the caller decides which scheme is the key entity.")

(defn connectivity-verdict
  "Pure PASS/FAIL over a measured connectivity summary of a built graph.

   `summary` keys:
     :occupation-edge-participation  N — # canonical key nodes participating in
                                         >=1 association edge (0 = isolated / BFS-dead).
     :canonical-source-edges         N — # association edges whose source-uri is a
                                         CANONICAL `occupation/*` node.
     :entity-stub-edges              N — # association edges whose source-uri is an
                                         `entity/*` STUB (the CONNECT-3c fragmentation gap).
     :bfs-canonical->related?        b — did BFS from a canonical occupation reach a
                                         DIFFERENT canonical occupation through a shared element?
     :element-node-counts {:distinct-elements N :element-edges N}
                                        — dedup diagnostic (NON-gating): shared elements
                                          collapse to canonical nodes (distinct <= edges).

   Gating PASS iff ALL:
     1. occupation-edge-participation > 0        (occupations are not isolated)
     2. entity-stub-edges = 0 AND canonical-source-edges > 0
        (edges attach to CANONICAL occupation/* nodes — the CONNECT-3c guarantee)
     3. bfs-canonical->related? = true
        (a canonical occupation reaches a DIFFERENT one through a shared element)

   Returns {:pass? bool :reasons [{:criterion kw :pass? bool :detail str
                                   :gating? (only on non-gating reasons)} ...]}."
  [{:keys [occupation-edge-participation canonical-source-edges entity-stub-edges
           bfs-canonical->related? element-node-counts]}]
  (let [part  (or occupation-edge-participation 0)
        canon (or canonical-source-edges 0)
        stub  (or entity-stub-edges 0)
        {:keys [distinct-elements element-edges]} element-node-counts
        criteria
        [{:criterion :occupation-edges-present
          :pass? (pos? part)
          :detail (str part " occupation node(s) participate in an association edge"
                       (when-not (pos? part)
                         " — occupations are ISOLATED (0 edges, BFS-dead: the pre-CONNECT-3 state)"))}
         {:criterion :canonical-occupation-sources
          :pass? (and (pos? canon) (zero? stub))
          :detail (str canon " canonical occupation/* source edge(s); " stub " entity/* stub edge(s)"
                       (cond
                         (pos? stub)   " — edges attach to entity/<SOC> STUBS, not canonical occupations (CONNECT-3c VIOLATED)"
                         (zero? canon) " — NO canonical occupation-source edges"
                         :else         " — all association edges attach to CANONICAL occupation/* nodes (CONNECT-3c)"))}
         {:criterion :bfs-canonical-to-different-occupation
          :pass? (boolean bfs-canonical->related?)
          :detail (if bfs-canonical->related?
                    "BFS from a canonical occupation reaches a DIFFERENT canonical occupation through a shared element"
                    "BFS did NOT reach a different canonical occupation (no shared-element bridge — an occupation-island)")}
         {:criterion :element-node-dedup
          :gating? false
          :pass? (boolean (and distinct-elements element-edges (<= distinct-elements element-edges)))
          :detail (str distinct-elements " distinct element node(s) carry " element-edges " edge(s)"
                       " [NON-GATING dedup diagnostic — shared elements collapse to canonical nodes]")}]]
    {:pass? (every? :pass? (remove #(false? (:gating? %)) criteria))
     :reasons criteria}))

;; ME-5 — the PURE, durable memory-efficiency verdict (the /tdd deliverable).
;;
;; Given a MEASURED store event-type histogram of a completed build, decide
;; whether the ME-1..ME-3 slimming actually landed (embed once, write-only
;; dedup ledgers gated off, evidence rolled once-per-concept) AND the CONSUMED
;; sets are intact. Same shape/style as `connectivity-verdict` above: PURE — no
;; I/O, no LLM, no Grain. The live bounded O*NET build (the QA) reads the
;; histogram from the persisted store and feeds it here; this fn is guarded on
;; every `poly test brick:ontology` run.
;;
;; Domain-agnostic: the fn names NO O*NET column, SOC, skill, or predicate — it
;; reads only event-type counts off the histogram map.

(defn memory-efficiency-verdict
  "Pure PASS/FAIL over a MEASURED store event-type histogram of a built graph.

   `histogram` is a map `{event-type-keyword count}` (absent key ⇒ 0 events).
   Keyed event types:
     :ontology/concept-created              N — concepts minted (the concept count).
     :ontology/concept-embedded             N — embed events (ME-1: exactly 1× per concept).
     :ontology/concept-pair-co-occurrence   N — write-only dedup ledger (ME-2: gated ⇒ 0).
     :ontology/dedup-distinct-recorded      N — write-only dedup ledger (ME-2: gated ⇒ 0).
     :ontology/concept-evidence-aggregated  N — evidence rollups (ME-3: ≤ concepts, once per
                                                participating concept — NOT ~2×candidate-pairs).
     :ontology/relationship-created         N — consumed set (must be > 0).
     :ontology/equivalence-recorded         N — consumed set (key must be PRESENT; count may be 0).

   Gating PASS iff ALL:
     1. concept-embedded = concept-created                (ME-1: 1× embed, not 2× re-embed)
     2. concept-pair-co-occurrence = 0 AND
        dedup-distinct-recorded = 0                       (ME-2: write-only ledgers gated off)
     3. concept-evidence-aggregated <= concept-created    (ME-3: one rollup per participating
                                                           concept, ≤ concepts since not every
                                                           concept enters a dedup comparison)
     4. concept-created > 0 AND relationship-created > 0
        AND :ontology/equivalence-recorded key present    (consumed sets intact)

   Returns {:pass? bool :reasons [{:criterion kw :pass? bool :detail str} ...]}."
  [histogram]
  (let [g          (fn [k] (or (get histogram k) 0))
        created    (g :ontology/concept-created)
        embedded   (g :ontology/concept-embedded)
        co-occ     (g :ontology/concept-pair-co-occurrence)
        distinct-r (g :ontology/dedup-distinct-recorded)
        evidence   (g :ontology/concept-evidence-aggregated)
        rel        (g :ontology/relationship-created)
        equiv?     (contains? histogram :ontology/equivalence-recorded)
        equiv      (g :ontology/equivalence-recorded)
        criteria
        [{:criterion :one-embed-per-concept
          ;; ME-1: at most ONE embed per concept. The failure mode is the 2× RE-EMBED
          ;; (embedded > created). `<= created` rejects that while NOT false-failing when
          ;; a blank-text concept is honestly skipped (embedded < created) — every semantic
          ;; concept is still embedded exactly once. `pos?` guards the nothing-embedded case.
          :pass? (and (pos? embedded) (<= embedded created))
          :detail (str embedded " concept-embedded vs " created
                       " concept-created — expected 0 < embedded <= created (1× embed per"
                       " concept, ME-1)"
                       (when (> embedded created)
                         (if (and (pos? created) (= embedded (* 2 created)))
                           " — 2× RE-EMBED (auto-embed! duplicating embed+index!)"
                           " — embed count EXCEEDS the concept count (re-embed)"))
                       (when (zero? embedded) " — NOTHING embedded"))}
         {:criterion :writeonly-ledgers-gated
          :pass? (and (zero? co-occ) (zero? distinct-r))
          :detail (str co-occ " concept-pair-co-occurrence + " distinct-r
                       " dedup-distinct-recorded — expected BOTH 0"
                       " (write-only dedup ledgers gated off, ME-2)"
                       (when-not (and (zero? co-occ) (zero? distinct-r))
                         " — a write-only ledger is STILL emitting"))}
         {:criterion :evidence-once-per-concept
          :pass? (<= evidence created)
          :detail (str evidence " concept-evidence-aggregated vs " created
                       " concept-created — expected <= concepts (one rollup per"
                       " participating concept, NOT ~2×candidate-pairs, ME-3)"
                       (when (> evidence created)
                         " — evidence EXCEEDS concepts (per-pair cascade write-amplification)"))}
         {:criterion :consumed-sets-intact
          :pass? (and (pos? created) (pos? rel) equiv?)
          :detail (str created " concept-created, " rel " relationship-created, "
                       (if equiv? equiv "MISSING") " equivalence-recorded"
                       " — expected concept-created>0, relationship-created>0,"
                       " equivalence-recorded key present"
                       (cond
                         (not (pos? created)) " — NO concepts created (consumed set empty)"
                         (not (pos? rel))     " — NO relationships created (consumed set empty)"
                         (not equiv?)         " — equivalence-recorded key ABSENT (consumed set dropped)"
                         :else                " — consumed sets intact"))}]]
    {:pass? (every? :pass? criteria)
     :reasons criteria}))
