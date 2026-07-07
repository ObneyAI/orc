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
