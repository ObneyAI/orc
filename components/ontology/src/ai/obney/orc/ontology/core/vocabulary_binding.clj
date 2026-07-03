(ns ai.obney.orc.ontology.core.vocabulary-binding
  "MT-7a — vocabulary binding + enforcement (the PURE core).

   ADR-0001: the model-spec's discovered `:entity-types` is the CANONICAL
   ENTITY-TYPE VOCABULARY for every container of a source. Per-container
   extraction authors were freelancing entity-type names (`Occupation` vs
   `job-zones/occupation`; case variants `job-element` vs `Job Element`) — so
   the same real-world entity minted distinct canonical URIs and never merged
   (ENTITY-TYPE FRAGMENTATION: one entity split across thousands of nodes).

   The enforcement is DETERMINISTIC normalized-EXACT matching against the
   vocabulary's `:type` + `:aliases`, using the SAME case/separator
   normalization GC-1 identity uses (`normalize-name`, the shared helper the
   extract subbehavior's `normalize-key-name` delegates to — one normalization,
   never two). A match SNAPS the emitted type to the canonical spelling; a
   non-match is EXCLUDED + surfaced (VOCABULARY FREELANCING — never silently
   landed, never fuzzy-snapped). NO substring/fuzzy matching anywhere:
   `job-zones/occupation` must NOT resolve to `Occupation` (over-merge risk —
   banned, see the ADR's rejected options).

   Empty/unparseable vocabulary = the extract orchestrator HARD-STOPS loudly
   (`empty-vocabulary?`) — extraction against no vocabulary is guaranteed 100%
   freelancing, never a silent proceed.

   Pure + total — NO Grain, NO LLM, unit-testable in isolation. Domain/format-
   agnostic (#12): the vocabulary is the runtime model-spec's own discovery;
   this ns names no domain type or column."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; The ONE normalization (shared with GC-1 identity — no fork)
;; ---------------------------------------------------------------------------

(defn normalize-name
  "Normalize a name (an entity-type, a column/attribute key) for case/separator-
   tolerant EXACT matching: lower-case the string form (a keyword's `name`, or
   the string itself) and strip every non-alphanumeric character. So `:MyKey`,
   `\"my_key\"`, `\"MY-KEY\"` all collapse to `\"mykey\"`; `job-element` ≡
   `Job Element`. This IS the GC-1 `normalize-key-name` normalization, housed
   here as the shared public helper (the extract subbehavior delegates to it) so
   vocabulary matching and canonical-URI identity can never drift apart.
   Deliberately NOT a similarity measure — two names either collapse to the
   same normalized string or they do not (normalized-EXACT, no fuzz). Returns
   nil for nil. Pure + total."
  [k]
  (when (some? k)
    (-> (if (keyword? k) (name k) (str k))
        (str/lower-case)
        (str/replace #"[^a-z0-9]" ""))))

;; ---------------------------------------------------------------------------
;; The vocabulary (from the model-spec)
;; ---------------------------------------------------------------------------

(defn coerce-entity-types
  "Coerce whatever the `:llm` Model node emitted for `:entity-types` into a
   clean vector of entity-type MAPS — the GC-10 Fix A coercion, housed here as
   the shared helper (`normalize-model-spec` in the extract subbehavior
   delegates to it — one coercion, never two). The C1 parse fragility means the
   SAME write may arrive as parsed Clojure data OR an un-parsed EDN STRING;
   a string is `edn/read-string`d back (a non-parse → `[]`, honest — #4/#5),
   and only well-formed MAP entries are kept. Pure + total — never throws."
  [ets]
  (let [ets (cond
              (vector? ets) ets
              (sequential? ets) (vec ets)
              (string? ets) (try (let [p (edn/read-string ets)]
                                   (cond (vector? p) p
                                         (sequential? p) (vec p)
                                         :else []))
                                 (catch Throwable _ []))
              :else [])]
    (vec (filter map? ets))))

(defn canonical-types
  "The declared CANONICAL ENTITY-TYPE VOCABULARY from a model-spec:
   `[{:type … :uri-keying-fields … :aliases …} …]` in declaration order.
   Tolerates the string-form `:entity-types` (C1 — `coerce-entity-types`) and
   `:aliases` when present (the model-spec schema is `{:closed false}`; absent →
   matching is on `:type` only). An entry with no usable (non-blank) `:type` is
   dropped honestly — it cannot bind anything. Pure + total."
  [model-spec]
  (->> (coerce-entity-types (:entity-types model-spec))
       (filter (fn [{:keys [type]}]
                 (seq (normalize-name type))))
       (vec)))

(defn empty-vocabulary?
  "True when the model-spec carries NO usable entity-type vocabulary post-
   normalize (nil / empty / unparseable `:entity-types`, or entries with no
   usable `:type`). An empty vocabulary means extraction would be guaranteed
   100% vocabulary freelancing — the extract orchestrator HARD-STOPS on it
   (loud, never a silent proceed — #5). Pure + total."
  [model-spec]
  (empty? (canonical-types model-spec)))

;; ---------------------------------------------------------------------------
;; Resolution — normalized-EXACT, snap-to-canonical-spelling, NO fuzz
;; ---------------------------------------------------------------------------

(defn resolve-entity-type
  "Resolve an emitted `entity-type` against the vocabulary (`canonical-types`
   output): normalized-EXACT match against each entry's `:type` and `:aliases`
   (the SAME `normalize-name` normalization GC-1 identity uses) → the entry's
   canonical `:type` SPELLING (never the alias/variant as emitted), or nil when
   nothing matches. First matching entry wins (declaration order —
   deterministic). NO substring/fuzzy matching: `job-zones/occupation` does NOT
   resolve to `Occupation`; a freelanced type stays unresolved so the caller
   can EXCLUDE + surface it. Pure + total."
  [vocab entity-type]
  (let [target (normalize-name entity-type)]
    (when (seq target)
      (some (fn [{:keys [type aliases]}]
              ;; aliases are matched ONLY when they arrive as a real collection —
              ;; a C1-mangled non-sequential :aliases is ignored (never iterated
              ;; as characters), so matching stays on :type alone for that entry.
              (let [aliases (when (sequential? aliases) aliases)]
                (when (or (= target (normalize-name type))
                          (some #(= target (normalize-name %)) aliases))
                  type)))
            vocab))))

;; ---------------------------------------------------------------------------
;; Binding — snap the matching drafts, EXCLUDE + surface the freelanced ones
;; ---------------------------------------------------------------------------

(defn bind-draft-types
  "Bind a concept-draft set to the vocabulary (`canonical-types` output):

     {:drafts   [<matching drafts, :entity-type SNAPPED to the canonical
                  spelling; all other fields untouched> …]
      :excluded [{:entity-type <as-emitted> :count n} …]}   ; appearance order

   A draft whose `:entity-type` resolves (normalized-EXACT — `resolve-entity-
   type`) proceeds with the CANONICAL spelling; a draft whose type does not
   resolve — including a draft with NO declared type (every author must emit a
   declared type, ADR-0001) — is EXCLUDED (it would only land as an unfixable
   fragment) and COUNTED per as-emitted type, so the freelancing is SURFACED,
   never silently landed (#5). All-canonical in → byte-identical out (behavior-
   preserving, the common case). Excluding ALL drafts yields `:drafts []`, so
   the flat `:concept-count` goes to 0 and the EXISTING EB9 0-draft resilience
   gate fires the re-ask — no separate retry loop (#8).

   An EMPTY vocabulary passes drafts through unchanged (`:excluded []`):
   enforcement against no vocabulary is undefined, and the LOUD stop for that
   state is the extract orchestrator's `empty-vocabulary?` hard stop — mass-
   excluding here would manufacture a fake 0-draft signal that mis-fires the
   EB9 gate (a corrupted measurement, not enforcement). Pure + total."
  [vocab drafts]
  (let [drafts (vec (or drafts []))]
    (if (empty? vocab)
      {:drafts drafts :excluded []}
      (let [decided (mapv (fn [draft]
                            (if-let [canon (resolve-entity-type vocab (:entity-type draft))]
                              {:draft (assoc draft :entity-type canon)}
                              {:excluded-type (:entity-type draft)}))
                          drafts)
            kept (into [] (keep :draft) decided)
            excluded-types (into [] (comp (filter #(contains? % :excluded-type))
                                          (map :excluded-type))
                                 decided)
            appearance-order (distinct excluded-types)
            counts (frequencies excluded-types)]
        {:drafts kept
         :excluded (mapv (fn [t] {:entity-type t :count (get counts t)})
                         appearance-order)}))))

;; ---------------------------------------------------------------------------
;; The AUTHOR contract (static prompt, runtime enumeration)
;; ---------------------------------------------------------------------------

(defn vocabulary-binding-guidance
  "The STATIC prompt block appended to BOTH extraction-author prompts (the
   resilient primary + robust AND the flat path — exactly the way
   `aggregation-author-guidance` is appended). The AUTHOR `:llm` node's
   instruction is baked at sheet build; the vocabulary itself arrives at
   RUNTIME as the node's `:model-spec` read — so the block ENUMERATES nothing
   here, it binds the author to the runtime enumeration. Domain-agnostic
   (#12): it names the discipline, never a domain type."
  []
  (str
   "\n\n*** CANONICAL ENTITY-TYPE VOCABULARY (binding — read carefully) ***\n"
   "The `model-spec` input you receive carries the discovered `:entity-types` — "
   "the canonical entity-type vocabulary for this source. Your `entity-type` — "
   "in the `aggregation-spec` AND in EVERY concept-draft your `transform-source` "
   "mints — MUST be one of the vocabulary's `:type` values, copied VERBATIM "
   "(character-for-character). NEVER invent, rename, prefix, suffix, or re-case "
   "a type; NEVER derive one from a container/sheet/table name. Every "
   "concept-draft MUST carry an `:entity-type`. A draft whose entity-type is "
   "not in the vocabulary is EXCLUDED at apply time (it can never merge with "
   "its entity's other drafts), so a freelanced type produces ZERO landed "
   "drafts. In your `reasoning`, FIRST name which of the model-spec's `:type` "
   "values this container's rows are, then author against exactly those."))
