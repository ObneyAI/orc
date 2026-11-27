(ns ai.obney.orc.ontology.core.read-models
  "Ontology event projections and tenant-scoped read-model access."
  (:require [ai.obney.grain.read-model-processor-v2.interface :as rmp]
            [ai.obney.orc.ontology.core.logic :as logic]
            [clojure.set :as set]))

;; --- Evidence guard ---

(rmp/defreadmodel :ontology claim-guard
  {:events logic/guard-events :version 1}
  [state event] (logic/claim-guard* state event))

(defn get-excluded-evidence
  "Every delta the evidence guard refused to record for this target,
   oldest first. `[]` when none."
  [ctx granularity target-id]
  (or (get-in (rmp/project ctx :ontology/claim-guard)
              [granularity target-id :excluded-evidence])
      []))

(defn get-claim-delta-refusals
  "Every consolidation refused for this target, oldest first. `[]` when
   none."
  [ctx granularity target-id]
  (or (get-in (rmp/project ctx :ontology/claim-guard)
              [granularity target-id :refusals])
      []))

;; --- Read models ---

(rmp/defreadmodel :ontology ontologies
  {:events logic/ontology-events, :version 1}
  [state event] (logic/ontologies* state event))

(rmp/defreadmodel :ontology concepts
  {:events logic/read-models-concept-events, :version 1}
  [state event] (logic/concepts* state event))

(rmp/defreadmodel :ontology tree-profiles
  {:events logic/read-models-tree-profile-events, :version 1}
  [state event] (logic/tree-profiles* state event))

(rmp/defreadmodel :ontology descriptions
  {:events (set/union logic/description-events logic/claim-events), :version 1}
  [state event] (logic/descriptions* state event))

(defn get-description
  "Return the CURRENT description body for the (granularity, target-id)
   target, or nil if no description exists.

   Granularity is one of :node-type, :node-instance, :tree-fingerprint.
   The target-id is whatever shape the granularity uses (keyword for
   :node-type, [sheet-id node-id] tuple for :node-instance, string for
   :tree-fingerprint).

   CC-3: the body is either wholesale-recorded (legacy) or ASSEMBLED
   from the target's claims — see `assemble-body`. Same slot, same
   shape, same consumers."
  [ctx granularity target-id]
  (get-in (rmp/project ctx :ontology/descriptions)
          [granularity target-id :current]))

(defn get-description-history
  "Return the chronological vector of all description versions ever
   recorded for the (granularity, target-id) target. Empty vector if
   none recorded."
  [ctx granularity target-id]
  (or (get-in (rmp/project ctx :ontology/descriptions)
              [granularity target-id :history])
      []))

(defn get-claims
  "CC-1 (ADR 0021): return the (granularity, target-id) target's current
   CLAIM SET — the vector of claims accumulated from every claim-delta
   event recorded against it, in the order they were added. Empty vector
   when the target has no claims.

   Mirrors `get-description`'s arg order; claims and the description body
   hang off the same target key, which is the spec's
   `LivingDescription.claims` relationship. Disjoint targets never share
   claims."
  [ctx granularity target-id]
  (or (get-in (rmp/project ctx :ontology/descriptions)
              [granularity target-id :claims])
      []))

(defn get-retired-claims
  "CC-2 (ADR 0021): return the target's `ClaimRetired` facts, oldest first.
   Empty vector when nothing has retired.

   Each entry is `{:claim <the claim as it stood> :reason :support-exhausted
   :retired-at <recorded-at>}`. Retirement is the ONLY removal path, so
   this vector is the complete record of everything this target has ever
   stopped believing — and it never shrinks. Retired claims are absent
   from `get-claims` deliberately: an assembled body must not have to
   filter tombstones."
  [ctx granularity target-id]
  (or (get-in (rmp/project ctx :ontology/descriptions)
              [granularity target-id :retired-claims])
      []))

(defn get-enforcing-claims
  "CC-7 (ADRs 0021/0022): return only the target's VALIDATED claims — the
   ones that have earned the right to influence retrieval ranking — ordered
   BEST-SUPPORTED FIRST. Empty vector when none.

   This is a SELECTION SURFACE, not a second projection: each element is the
   same claim map `get-claims` returns. What it removes is every claim that
   has not earned enforcement, which under ADR 0022 is the whole safety
   property — a negative claim is a retrieval-SUPPRESSION lever, so an
   unproven assertion reachable by a ranker would be the exact failure this
   arc exists to prevent, wearing new clothes. Candidates stay fully visible
   to the model through `get-claims` and the assembled body; they cannot mute
   anything.

   THE PRODUCTION CONSUMER IS CC-9a, and it is the only one: EL-2's
   `enrich-candidate-evidence` stamps this function's result onto a
   claim-backed candidate as `::domain-penalty/enforcing-avoid-when` — a
   `:guard` claim contributes its `:content`, a `:weakness` claim its
   `:context-guard`, which are precisely the two shapes `assemble-body` writes
   into the body and `avoid-strings` unions. Between CC-7 and CC-9a this
   function had ZERO consumers, so the safety property above was documented
   rather than true: the assembled body carried every claim regardless of
   status straight into the penalty. Guarded now by
   `cc9a-enforcement-gate-test`.

   THE ORDER IS PART OF THE CONTRACT and deliberately differs from
   `get-claims`, which preserves insertion order. The consumer here is a
   RANKER (CC-9 wires this into EL-5's domain penalty), and CC-3 established
   that consumers downstream truncate in the order they are handed — EL-2's
   `enrich-candidate-evidence` takes 3 in body order without sorting. Ranking
   at the source means the strongest claim survives every such truncation.
   The tie-break is `:claim-id`, deterministic from the recording event, so a
   rebuild from the log reproduces a byte-identical order."
  [ctx granularity target-id]
  (into []
        (logic/read-models-by-earned-support
          (filter #(= :validated (:status %))
                  (get-claims ctx granularity target-id)))))

(defn get-claim-set-version
  "CC-1 (ADR 0021): return the target's current claim-set version — the
   count of claim-delta events recorded against it. 0 when none.

   This is the value a consolidation must carry back when it records its
   deltas; the append CAS compares it, so a consolidation that reasoned
   over a stale claim set loses instead of silently overwriting."
  [ctx granularity target-id]
  (or (get-in (rmp/project ctx :ontology/descriptions)
              [granularity target-id :claim-set-version])
      0))

(rmp/defreadmodel :ontology consolidation-thresholds
  {:events #{:ontology/consolidation-threshold-set} :version 1}
  [state event] (logic/consolidation-thresholds* state event))

(defn get-consolidation-threshold
  "Return the configured threshold for a target-type, or the default 10."
  [ctx target-type]
  (or (get (rmp/project ctx :ontology/consolidation-thresholds) target-type)
      logic/default-consolidation-threshold))

(rmp/defreadmodel :ontology living-description-enabled
  {:events #{:ontology/living-description-enabled-set} :version 1}
  [state event] (logic/living-description-enabled* state event))

(defn get-living-description-enabled?
  "Return the current Living Description opt-in flag (default false)."
  [ctx]
  (boolean (:enabled? (rmp/project ctx :ontology/living-description-enabled))))

(rmp/defreadmodel :ontology consolidation-budgets
  {:events #{:ontology/consolidation-budget-set} :version 1}
  [state event] (logic/consolidation-budgets* state event))

(defn get-consolidation-budget
  "Return the configured hourly budget for a target-type, or the default 100."
  [ctx target-type]
  (or (get (rmp/project ctx :ontology/consolidation-budgets) target-type)
      logic/default-consolidation-budget))

(rmp/defreadmodel :ontology evidence-token-budget
  {:events #{:ontology/evidence-token-budget-set} :version 1}
  [state event] (logic/evidence-token-budget* state event))

(defn get-evidence-token-budget
  "Return the configured evidence token budget, or the DERIVED default
   (`default-evidence-token-budget`) when no override has been set via
   :ontology/set-evidence-token-budget."
  [ctx]
  (or (:budget-tokens (rmp/project ctx :ontology/evidence-token-budget))
      logic/default-evidence-token-budget))

(rmp/defreadmodel :ontology reflection-evidence-exclusions
  {:events #{:ontology/reflection-evidence-excluded} :version 1}
  [state event] (logic/reflection-evidence-exclusions* state event))

(defn get-reflection-evidence-exclusions
  "PR-2 (BoundedReflectionEvidence): every budget-cut exclusion record for
   this target, oldest first; [] when none — a zero-exclusion consolidation
   emitted nothing, so nothing is here to mislead."
  [ctx target-type target-id]
  (or (get (rmp/project ctx :ontology/reflection-evidence-exclusions)
           [target-type target-id])
      []))

(rmp/defreadmodel :ontology reindex-config
  {:events #{:ontology/reindex-config-set} :version 1}
  [state event] (logic/reindex-config* state event))

(defn get-reindex-config
  "Return the current re-index config (merge of defaults + any
   :ontology/reindex-config-set event)."
  [ctx]
  (merge logic/default-reindex-config
         (rmp/project ctx :ontology/reindex-config)))

(rmp/defreadmodel :ontology reindex-state
  {:events #{:ontology/node-type-description-updated
             :ontology/node-instance-description-updated
             :ontology/tree-description-updated
             :ontology/claim-deltas-recorded
             :colbert/index-created}
   :version 1}
  [state event] (logic/reindex-state* state event))

(defn get-reindex-state
  "Return the current re-index state — {:events-since-last-rebuild N
   :last-rebuild-timestamp ISO-string :index-built? bool}."
  [ctx]
  (merge logic/initial-reindex-state
         (rmp/project ctx :ontology/reindex-state)))

(rmp/defreadmodel :ontology recent-consolidations
  {:events #{:ontology/node-type-description-updated
             :ontology/node-instance-description-updated
             :ontology/tree-description-updated
             :ontology/claim-deltas-recorded
             :ontology/description-consolidation-failed}
   :version 1}
  [state event] (logic/recent-consolidations* state event))

(defn get-recent-consolidation-count
  "Return how many consolidation attempts — successes (:*-description-updated,
   :ontology/claim-deltas-recorded) AND terminal failures
   (:ontology/description-consolidation-failed, CC-28) — have fired for the
   given target-type in the rolling last-hour window. Used by the
   consolidator's budget gate: FailuresConsumeBudget means a dying target
   is throttled exactly as a busy healthy one is, never reported idle."
  [ctx target-type]
  (let [now (java.time.Instant/now)
        cutoff (.minusSeconds now 3600)
        all (get (rmp/project ctx :ontology/recent-consolidations) target-type [])]
    (->> all
         (keep logic/ts->instant)
         (filter #(.isAfter ^java.time.Instant % cutoff))
         count)))

(rmp/defreadmodel :ontology consolidation-request-ledger
  {:events (conj logic/consolidation-outcome-event-types
                 :ontology/consolidation-requested)
   :version 1}
  [state event] (logic/consolidation-request-ledger* state event))

(defn get-consolidation-outcome
  "PR-2: the durable outcome that answered the given consolidation request.

   Returns {:status <kw> :events [{:event-type … :at … (:reason …)} …]}.
   :status is derived from the outcome events' types, one command batch =
   one outcome (a recorded batch's ride-along exclusions do not make it two):

     :deltas-recorded       — :ontology/claim-deltas-recorded present
     :description-updated   — a *-description-updated present
     :failed                — a failure record present
     :refused               — the stale-refusal fact present
     :skipped               — the budget gate answered
     :claim-set-unchanged   — ONLY promotion-evidence-excluded events (the
                              spec's ClaimSetUnchanged exclusion shape)
     :none                  — NO outcome: the 08-18 defect shape"
  [ctx request-id]
  (let [events (get-in (rmp/project ctx :ontology/consolidation-request-ledger)
                       [:outcomes request-id]
                       [])
        types (into #{} (map :event-type) events)
        status (cond
                 (empty? events) :none
                 (types :ontology/claim-deltas-recorded) :deltas-recorded
                 (some types [:ontology/node-type-description-updated
                              :ontology/node-instance-description-updated
                              :ontology/tree-description-updated]) :description-updated
                 (types :ontology/description-consolidation-failed) :failed
                 (types :ontology/claim-deltas-refused) :refused
                 (types :ontology/consolidation-skipped) :skipped
                 :else :claim-set-unchanged)]
    {:status status :events events}))

(defn get-unanswered-consolidation-requests
  "PR-2: every consolidation request with NO durable outcome, oldest first —
   the query that makes the 08-18 vanished request a detectable defect
   instead of an ambiguity.

   opts:
     :older-than-ms — only requests at least this old (0 = all); callers
                      pass the orphan grace period so an in-flight
                      consolidation is not reported dead while its LLM call
                      is still legitimately running
     :target-type / :target-id — optional scope filters

   Each entry: {:request-id … :target-type … :target-id … :requested-at <str>}."
  [ctx {:keys [older-than-ms target-type target-id]
        :or {older-than-ms 0}}]
  (let [ledger (rmp/project ctx :ontology/consolidation-request-ledger)
        cutoff (.minusMillis (java.time.Instant/now) (long older-than-ms))]
    (->> (:requests ledger)
         (remove (fn [[rid _]] (seq (get-in ledger [:outcomes rid]))))
         (keep (fn [[rid {:keys [requested-at] :as r}]]
                 (let [inst (logic/ts->instant requested-at)]
                   (when (and inst
                              (not (.isAfter ^java.time.Instant inst cutoff))
                              (or (nil? target-type) (= target-type (:target-type r)))
                              (or (nil? target-id) (= target-id (:target-id r))))
                     (assoc r :request-id rid)))))
         (sort-by :requested-at)
         vec)))

(rmp/defreadmodel :ontology consolidation-delta-counters
  {:events #{:sheet/node-execution-completed
             :sheet/rlm-tree-execution-completed
             :ontology/tree-class-occurrence-recorded
             :ontology/consolidation-requested}
   :version 1}
  [state event] (logic/consolidation-delta-counters* state event))

(defn get-consolidation-delta
  "Return the current delta-counter (events-since-last-consolidation)
   for the (target-type, target-id) target. Returns 0 when no events
   have ticked the counter."
  [ctx target-type target-id]
  (or (get-in (rmp/project ctx :ontology/consolidation-delta-counters)
              [target-type target-id :delta])
      0))

(defn get-consolidation-total
  "Return the lifetime total count of source events for the
   (target-type, target-id) target. Used by the CAS guard on
   consolidation-requested emissions to derive the crossing-number
   that enforces exactly-once-per-threshold-crossing across
   concurrent processor handlers.

   RR-23: for a `:tree-class` target, `[:tree-class target-id :total]` is
   bumped by exactly one event type — `:ontology/tree-class-occurrence-
   recorded` events whose `:assigned-tree-id` matches (`bump-counter`,
   the `consolidation-delta-counters*` multimethod). `:sheet/node-
   execution-completed` and `:sheet/rlm-tree-execution-completed` bump
   `:node-type`/`:node-instance`/`:tree-fingerprint` paths, never
   `:tree-class`; `:ontology/consolidation-requested` touches only
   `:delta` at its own target, never `:total`. So querying ONLY that one
   type, scoped by the `[:description-target target-id]` tag it already
   carries, through the read-model's OWN registered reducer (`rmp/project`
   `{:queries [...]}`, the engine's documented scope override — not a
   redefinition of the read-model), yields a state PROVABLY IDENTICAL at
   `[:tree-class target-id :total]` to the full unscoped fold, without
   growing with other targets' node-completion/bookend/consolidation-
   request volume. This was the dominant cost in `maybe-harvest!`'s
   pre-gate (measured: it alone accounted for the RR-23 baseline's 48->120
   promotion-path growth) — the read-model's DEFINITION is unchanged;
   only this :tree-class call site's query is narrowed. Other target-types
   have no such tag and stay on the general (unscoped) path."
  [ctx target-type target-id]
  (or (get-in (if (= target-type :tree-class)
                (rmp/project ctx :ontology/consolidation-delta-counters
                             {:queries [{:types #{:ontology/tree-class-occurrence-recorded}
                                         :tags #{[:description-target target-id]}}]})
                (rmp/project ctx :ontology/consolidation-delta-counters))
              [target-type target-id :total])
      0))

(rmp/defreadmodel :ontology tree-class-judge-averages
  {:events #{:judge/score-emitted
             :ontology/task-classified
             :ontology/tree-class-occurrence-recorded}
   :version 1}
  [state event] (logic/tree-class-judge-averages* state event))

(defn get-tree-class-for-sheet
  "CV-2 (ADR 0017 decision 3): return the :tree-class id assigned to
   `source-sheet-id`, or nil when the sheet was never classified.

   Reuses the tree-class-judge-averages read-model's :sheet->class map,
   which the :ontology/task-classified reducer populates UNCONDITIONALLY
   (independent of any judge scores) — exactly the sheet->class join the
   EL-4 read-model already performs. The post-emit enrichment processor
   uses this to route an emitted tree's worked-DSL onto the class the task
   was assigned to."
  [ctx source-sheet-id]
  (get-in (rmp/project ctx :ontology/tree-class-judge-averages)
          [:sheet->class source-sheet-id]))

(defn get-tree-class-for-occurrence
  "RR-20: return the :tree-class id assigned to the
   `[source-sheet-id source-tick-id]` OCCURRENCE, or nil when that occurrence
   was never classified.

   Reuses SJ-1's `:occurrence->class` map (the same per-occurrence join
   `tree-class-judge-averages-projection` already uses to attribute judge
   scores) instead of `get-tree-class-for-sheet`'s bare-sheet-id join. A
   static task-shape's sheet-id is shared across every turn, so a bare-sheet
   join can attribute a Phase-2 bookend to whichever class the shared host
   sheet was MOST RECENTLY (re)classified to — a different turn's class. The
   occurrence pair is what uniquely names the turn the bookend belongs to.
   The post-emit enrichment processor uses this instead of the sheet-only
   join so a bookend can never be attributed to a sibling classification."
  [ctx source-sheet-id source-tick-id]
  (get-in (rmp/project ctx :ontology/tree-class-judge-averages)
          [:occurrence->class [source-sheet-id source-tick-id]]))

(defn get-tree-class-judge-averages
  "EL-4: return {judge-name -> mean-score} across this tree-class's lifetime,
   or nil when no scores exist for the class. Parity target: the
   consolidator's tree-class-aggregate-metrics :judge-averages."
  [ctx tree-class-id]
  (let [state (rmp/project ctx :ontology/tree-class-judge-averages)
        by-class (logic/tree-class-judge-averages-projection state)]
    (when-let [judges (get by-class tree-class-id)]
      (not-empty
        (into {}
              (map (fn [[judge-name {:keys [sum count]}]]
                     [judge-name (/ sum (double count))]))
              judges)))))

(defn get-tree-class-judge-recent-averages
  "CC-24b (ADR 0029) — the DIMENSION axis's input: {judge-name -> mean over
   that judge's most recent `window` scored occurrences of this tree-class},
   or nil when the class has no judge scores in its retained window.

   This is the companion to `get-tree-class-judge-averages`, not its
   replacement: the LIFETIME accessor keeps its meaning (and its parity with
   the consolidator's aggregate) and is still what the harvest log records,
   while the harvest GATE reads this one. Measured (CC-24a): the lifetime mean
   against the floor fired 0 times in 105 real positions at BOTH 0.80 and
   0.75, because a mean can never forget — the dominant class would have
   needed ~97 consecutive perfect occurrences, and its zero-blocks were
   produced by ENGINE defects since fixed. The trailing-10 mean fires 38/105
   and abstains 67/105 on the same data, which is ADR 0027's acceptance bar.

   A judge with fewer than `window` scored occurrences is averaged over what
   it has; requiring a FULL window is the consistency axis's job (ALL-of-W,
   ratified unchanged) and adding it here would change no verdict on the real
   corpus (measured: no prefix shorter than the window clears the floor).

   Bounded by `recent-occurrence-bound` — see its docstring for why the bound
   is where it is."
  [ctx tree-class-id window]
  (let [state (rmp/project ctx :ontology/tree-class-judge-averages)
        by-judge (logic/tree-class-recent-judge-scores-projection state tree-class-id)]
    (not-empty
      (into {}
            (keep (fn [[judge-name scores]]
                    (let [w (take-last window scores)]
                      (when (seq w)
                        [judge-name (/ (reduce + 0.0 w) (double (count w)))]))))
            by-judge))))

(rmp/defreadmodel :ontology node-experiences
  {:events logic/read-models-node-learning-events, :version 1}
  [state event] (logic/node-experiences* state event))

(defn get-concepts
  "Get all concepts, optionally filtered by scope and/or ontology-id.

   Options:
     :scope       - Filter by concept scope
     :broader-uri - Filter by concepts with this URI in their broader set
     :ontology-id - Filter by single ontology-id
     :ontology-ids - Filter by multiple ontology-ids (returns union)"
  [ctx & [{:keys [scope broader-uri ontology-id ontology-ids]}]]
  (let [normalize-id #(if (string? %)
                        (java.util.UUID/nameUUIDFromBytes (.getBytes ^String % "UTF-8"))
                        %)
        all-concepts (map #(dissoc % :ai.obney.orc.ontology.core.read-models/projection-order)
                          (vals (rmp/project ctx :ontology/concepts)))
        ont-id-set (cond
                     ontology-ids (set (map normalize-id ontology-ids))
                     ontology-id #{(normalize-id ontology-id)}
                     :else nil)]
    (cond->> all-concepts
      scope (filter #(= scope (:scope %)))
      broader-uri (filter #(contains? (:broader %) broader-uri))
      ont-id-set (filter #(contains? ont-id-set (:ontology-id %))))))

(defn get-ontology [ctx ontology-id]
  (get (rmp/project ctx :ontology/ontologies) ontology-id))

(defn list-ontologies [ctx]
  (->> (rmp/project ctx :ontology/ontologies)
       vals
       (sort-by (juxt :created-at :ontology-id))
       vec))

(defn ontology-exists? [ctx ontology-id]
  (boolean (get-ontology ctx ontology-id)))

(defn get-concept-by-uri
  "Get a concept by ontology identity and URI. The legacy two-argument form
   returns a value only when the URI is globally unambiguous."
  ([ctx uri]
   (let [matches (filter #(= uri (:uri %))
                         (vals (rmp/project ctx :ontology/concepts)))]
     (when (= 1 (count matches))
       (dissoc (first matches) :ai.obney.orc.ontology.core.read-models/projection-order))))
  ([ctx ontology-id uri]
   (let [normalized-id (if (string? ontology-id)
                         (java.util.UUID/nameUUIDFromBytes (.getBytes ^String ontology-id "UTF-8"))
                         ontology-id)]
     (some->> (vals (rmp/project ctx :ontology/concepts))
              (filter #(and (= uri (:uri %))
                            (= normalized-id (:ontology-id %))))
              first
              (#(dissoc % :ai.obney.orc.ontology.core.read-models/projection-order))))))

(defn get-tree-profile
  "Get profile for a specific tree."
  [ctx tree-id]
  (get (rmp/project ctx :ontology/tree-profiles {:tags #{[:tree tree-id]}}) tree-id))

(defn get-all-tree-profiles
  "Get all tree profiles."
  [ctx]
  (rmp/project ctx :ontology/tree-profiles))

(defn get-node-type-learnings
  "Get aggregated learnings for a specific node type."
  [ctx node-type]
  (get (rmp/project ctx :ontology/node-experiences {:tags #{[:node-type node-type]}}) node-type))

(defn get-all-node-learnings
  "Get all node learnings aggregated by type."
  [ctx]
  (rmp/project ctx :ontology/node-experiences))

(defn find-trees-by-problem
  "Find trees that solve a specific problem type."
  [ctx problem-uri]
  (let [profiles (get-all-tree-profiles ctx)]
    (->> profiles
         vals
         (filter (fn [p]
                   (some #(= problem-uri (:problem-uri %)) (:solves p)))))))

(defn find-trees-with-weakness
  "Find trees that have a specific weakness."
  [ctx failure-uri]
  (let [profiles (get-all-tree-profiles ctx)]
    (->> profiles
         vals
         (filter (fn [p]
                   (some #(= failure-uri (:failure %)) (:weaknesses p)))))))

(defn get-narrower-concepts
  "Get all concepts that are narrower than the given URI."
  [ctx uri]
  (get-in (rmp/project ctx :ontology/concepts) [uri :narrower] #{}))

(defn get-broader-concepts
  "Get all concepts that are broader than the given URI."
  [ctx uri]
  (get-in (rmp/project ctx :ontology/concepts) [uri :broader] #{}))

(defn concept-statistics
  "Get statistics about the concept graph."
  [ctx]
  (let [concept-graph (rmp/project ctx :ontology/concepts)
        by-scope (group-by :scope (vals concept-graph))]
    {:total-concepts (count concept-graph)
     :by-scope (into {} (map (fn [[k v]] [k (count v)]) by-scope))
     :with-indicators (count (filter #(seq (:indicators %)) (vals concept-graph)))}))

(defn tree-profile-statistics
  "Get statistics about tree profiles."
  [ctx]
  (let [profiles (get-all-tree-profiles ctx)]
    {:total-profiles (count profiles)
     :with-strengths (count (filter #(seq (:strengths %)) (vals profiles)))
     :with-weaknesses (count (filter #(seq (:weaknesses %)) (vals profiles)))
     :with-problem-mappings (count (filter #(seq (:solves %)) (vals profiles)))}))

(defn node-learning-statistics
  "Get statistics about node learning."
  [ctx]
  (let [learnings (get-all-node-learnings ctx)]
    {:node-types-with-learnings (count learnings)
     :by-node-type (into {}
                         (for [[node-type patterns] learnings]
                           [node-type
                            {:pattern-types (count patterns)
                             :total-patterns (reduce + 0
                                                     (for [[_ {:keys [effective ineffective]}] patterns]
                                                       (+ (count effective) (count ineffective))))}]))}))

(rmp/defreadmodel :ontology concept-embeddings
  {:events #{:ontology/concept-embedded}, :version 1}
  [state event] (logic/concept-embeddings* state event))

(rmp/defreadmodel :ontology tree-profile-embeddings
  {:events #{:ontology/tree-profile-embedded}, :version 1}
  [state event] (logic/tree-profile-embeddings* state event))

(rmp/defreadmodel :ontology embedding-config
  {:events #{:ontology/embedding-model-configured}, :version 1}
  [state event] (logic/embedding-config* state event))

(defn get-concept-embedding
  "Get embedding for a specific concept by URI."
  [ctx uri]
  (get (rmp/project ctx :ontology/concept-embeddings) uri))

(defn get-all-concept-embeddings
  "Get all concept embeddings, optionally filtered by scope and/or ontology-id.

   Options:
     :scope       - Filter by concept scope
     :ontology-id - Filter by single ontology-id
     :ontology-ids - Filter by multiple ontology-ids (returns union)"
  [ctx & [{:keys [scope ontology-id ontology-ids]}]]
  (let [all-embeddings (cond->> (rmp/project ctx :ontology/concept-embeddings)
                         scope (into {} (filter #(= scope (:scope (val %))))))
        ont-id-set (cond
                     ontology-ids (set ontology-ids)
                     ontology-id #{ontology-id}
                     :else nil)]
    (if ont-id-set
      (into {} (filter #(contains? ont-id-set (:ontology-id (val %))) all-embeddings))
      all-embeddings)))

(defn get-tree-profile-embedding
  "Get embedding for a specific tree profile."
  [ctx tree-id]
  (get (rmp/project ctx :ontology/tree-profile-embeddings {:tags #{[:tree tree-id]}}) tree-id))

(defn get-all-tree-profile-embeddings
  "Get all tree profile embeddings."
  [ctx]
  (rmp/project ctx :ontology/tree-profile-embeddings))

(defn get-embedding-config
  "Get embedding model configuration for a scope."
  [ctx scope]
  (get (rmp/project ctx :ontology/embedding-config {:tags #{[:scope scope]}}) scope))

(defn embedding-statistics
  "Get statistics about embeddings."
  [ctx]
  (let [embeddings (rmp/project ctx :ontology/concept-embeddings)
        profile-embs (rmp/project ctx :ontology/tree-profile-embeddings)
        configs (rmp/project ctx :ontology/embedding-config)]
    {:concept-embeddings-count (count embeddings)
     :tree-profile-embeddings-count (count profile-embs)
     :configured-scopes (keys configs)
     :by-model (frequencies (map :model-id (vals embeddings)))}))

(rmp/defreadmodel :ontology ontology-colbert-indexes
  {:events logic/ontology-colbert-events :version 1}
  [state event] (logic/ontology-colbert-indexes* state event))

(defn get-colbert-index-for-ontology
  "Get the ColBERT index-id and metadata associated with an ontology.

   Returns nil if no ColBERT index exists for this ontology."
  [ctx ontology-id]
  (get (rmp/project ctx :ontology/ontology-colbert-indexes
                    {:tags #{[:ontology ontology-id]}})
       ontology-id))

(defn list-ontology-colbert-indexes
  "List all ontology-to-ColBERT index mappings."
  [ctx]
  (rmp/project ctx :ontology/ontology-colbert-indexes))

(rmp/defreadmodel :ontology ontology-embedding-state
  {:events logic/ontology-embedding-events :version 1}
  [state event] (logic/ontology-embedding-state* state event))

(defn get-embedding-state-for-ontology
  "Get the embedding state for an ontology.

   Returns nil if no embeddings exist for this ontology.
   Returns map with:
     {:embedded? true
      :embedded-count N
      :embedding-fields [:label :description ...]
      :model-id \"...\"
      :embedded-at \"...\"}"
  [ctx ontology-id]
  (get (rmp/project ctx :ontology/ontology-embedding-state
                    {:tags #{[:ontology ontology-id]}})
       ontology-id))

(defn ontology-has-embeddings?
  "Check if an ontology has been embedded (ready for RRF search)."
  [ctx ontology-id]
  (boolean (:embedded? (get-embedding-state-for-ontology ctx ontology-id))))

(defn list-ontology-embedding-states
  "List all ontology embedding states."
  [ctx]
  (rmp/project ctx :ontology/ontology-embedding-state))

(defn get-embedded-ontologies
  "Get all ontology-ids that have embeddings."
  [ctx]
  (->> (list-ontology-embedding-states ctx)
       (filter (fn [[_id state]] (:embedded? state)))
       (map first)
       set))

(rmp/defreadmodel :ontology learned-rules
  {:events logic/read-models-learned-rule-events, :version 1}
  [state event] (logic/learned-rules* state event))

(defn get-tree-rules
  "Get learned rules for a specific tree."
  [ctx tree-id]
  (get (rmp/project ctx :ontology/learned-rules {:tags #{[:tree tree-id]}}) tree-id))

(defn get-all-learned-rules
  "Get all learned rules for all trees."
  [ctx]
  (rmp/project ctx :ontology/learned-rules))

(defn find-rules-by-problem
  "Find rules that were extracted for a specific problem type."
  [ctx problem-type]
  (let [all-rules (get-all-learned-rules ctx)]
    (->> all-rules
         vals
         (apply concat)
         (filter #(= problem-type (:problem-type %)))
         vec)))

(defn find-rules-by-condition
  "Find rules that match given condition criteria.

   conditions: Map of condition key-value pairs to match
   Returns rules where all specified conditions are present in the rule's condition map."
  [ctx conditions]
  (let [all-rules (get-all-learned-rules ctx)]
    (->> all-rules
         vals
         (apply concat)
         (filter (fn [rule]
                   (let [rule-conditions (:condition rule)]
                     (every? (fn [[k v]]
                               (= v (get rule-conditions k)))
                             conditions))))
         vec)))

(defn learned-rules-statistics
  "Get statistics about learned rules."
  [ctx]
  (let [all-rules (get-all-learned-rules ctx)
        all-rules-flat (apply concat (vals all-rules))]
    {:total-rules (count all-rules-flat)
     :trees-with-rules (count all-rules)
     :by-problem-type (frequencies (map :problem-type all-rules-flat))
     :by-domain-type (frequencies (keep :domain-type all-rules-flat))
     :avg-confidence (when (seq all-rules-flat)
                       (/ (reduce + 0 (map :confidence all-rules-flat))
                          (count all-rules-flat)))
     :avg-success-rate (when (seq all-rules-flat)
                         (/ (reduce + 0 (map :success-rate all-rules-flat))
                            (count all-rules-flat)))}))

(rmp/defreadmodel :site registry
  {:events logic/read-models-site-registry-events, :version 1}
  [state event] (logic/site-registry* state event))

(defn get-site-by-domain
  "Get a site by its domain."
  [ctx domain]
  (get-in (rmp/project ctx :site/registry) [:by-domain domain]))

(defn get-all-sites
  "Get all registered sites."
  [ctx]
  (vals (get (rmp/project ctx :site/registry) :by-domain {})))

(defn get-trusted-sites
  "Get sites with trust score above threshold, sorted by trust.

   Args:
   - min-trust: Minimum trust score (default 0.5)
   - limit: Maximum sites to return"
  [ctx & [{:keys [min-trust limit] :or {min-trust 0.5}}]]
  (let [state (rmp/project ctx :site/registry)
        all-sites (vals (:by-domain state))
        trusted (->> all-sites
                     (filter #(>= (:trust-score % 0) min-trust))
                     (sort-by :trust-score >))]
    (if limit
      (take limit trusted)
      trusted)))

(defn get-site-patterns
  "Get learned patterns for a site.

   Args:
   - domain: Site domain
   - pattern-type: Optional filter by pattern type"
  [ctx domain & [{:keys [pattern-type]}]]
  (let [patterns (get-in (rmp/project ctx :site/registry) [:patterns domain] [])]
    (if pattern-type
      (filter #(= pattern-type (:pattern-type %)) patterns)
      patterns)))

(defn get-sites-requiring-headed
  "Get sites that require headed browser mode."
  [ctx]
  (->> (get-all-sites ctx)
       (filter :requires-headed)))

(defn site-registry-statistics
  "Get statistics about the site registry."
  [ctx]
  (let [state (rmp/project ctx :site/registry)
        sites (vals (:by-domain state))
        patterns (get state :patterns {})]
    {:total-sites (count sites)
     :by-category (frequencies (map :category sites))
     :by-discovered-via (frequencies (map :discovered-via sites))
     :sites-requiring-headed (count (filter :requires-headed sites))
     :total-patterns (reduce + (map count (vals patterns)))
     :avg-trust-score (when (seq sites)
                        (/ (reduce + (map :trust-score sites))
                           (count sites)))
     :sites-with-extractions (count (filter #(> (:extraction-count % 0) 0) sites))}))
