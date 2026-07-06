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
            [clojure.data.json :as json]
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

(defn- keywordize-entry-keys
  "Defensively keywordize a kept entry MAP's TOP-LEVEL keys, so downstream
   (`canonical-types`, GC-1) always reads `:type` / `:uri-keying-fields` as
   keywords regardless of the parse path (a residual string key `\"type\"` →
   `:type`). Non-maps pass through untouched (they are filtered out anyway).
   Existing keyword keys are preserved verbatim. Pure + total."
  [m]
  (if (map? m)
    (reduce-kv (fn [acc k v]
                 (assoc acc (if (keyword? k) k (keyword (str k))) v))
               {} m)
    m))

(defn coerce-entity-types
  "Coerce whatever the `:llm` Model node emitted for `:entity-types` into a
   clean vector of entity-type MAPS — the GC-10 Fix A coercion, housed here as
   the shared helper (`normalize-model-spec` in the extract subbehavior
   delegates to it — one coercion, never two). The C1 parse fragility means the
   SAME write may arrive as parsed Clojure data OR an un-parsed STRING, and the
   string may be EITHER EDN (keyword keys) OR JSON (string keys — the MT-10
   diagnostic-PROVEN crossing shape, e.g. `\"[{\\\"type\\\": \\\"Occupation\\\"}]\"`
   — which `edn/read-string` CANNOT parse because of JSON's `\"key\":` colon).
   A string is recovered by trying, in order: (1) EDN (`edn/read-string`);
   (2) clean JSON (`json/read-str` with `:key-fn keyword`, so object keys become
   keywords); (3) the JSON/EDN HYBRID (the MT-10 REVISION live-PROVEN shape —
   JSON object syntax with string keys `\"type\":` carrying a BARE EDN keyword
   VALUE `:canonical-row-filter`, which NEITHER parser accepts: EDN chokes on
   the `\"key\":` colon, JSON chokes on the unquoted `:keyword`); (4) `[]`
   (honest — genuinely-unparseable → the loud hard stop stands, #4/#5). The
   hybrid step deterministically drops the JSON field-separator colon after each
   quoted KEY (`\"k\":` → `\"k\" `), turning the hybrid into valid EDN (EDN
   allows string keys + keyword values), then reads it as EDN. All parses are
   GUARDED (never throw). Only well-formed MAP entries are kept, and each kept
   entry's top-level keys are keywordized so downstream reads
   `:type`/`:uri-keying-fields` as keywords on EVERY path. Pure + total — never
   throws."
  [ets]
  (letfn [(seq->vec [p] (cond (vector? p) p
                              (sequential? p) (vec p)
                              :else nil))
          (try-edn [s] (try (seq->vec (edn/read-string s)) (catch Throwable _ nil)))
          (try-json [s] (try (seq->vec (json/read-str s :key-fn keyword))
                             (catch Throwable _ nil)))
          ;; HYBRID recovery. Drop the field-separator colon after each quoted
          ;; JSON KEY so the string becomes valid EDN, then read as EDN. The
          ;; regex matches a WHOLE quoted string (escaped-quote-aware:
          ;; `"(?:[^"\\]|\\.)*"`) followed by optional whitespace and `:`; the
          ;; colon is the match's last char, dropped + replaced with a space.
          ;; It CANNOT corrupt a colon inside a quoted VALUE: a value string is
          ;; always followed by `,`/`}`/`]` (never `:`), and any colon WITHIN a
          ;; value lives BETWEEN the matched quotes, never after the closing one
          ;; — so only a genuine `"key":` separator is ever rewritten.
          (try-hybrid [s]
            (try (-> (str/replace s #"\"(?:[^\"\\]|\\.)*\"\s*:"
                                  (fn [m] (str (subs m 0 (dec (count m))) " ")))
                     (edn/read-string)
                     (seq->vec))
                 (catch Throwable _ nil)))]
    (let [ets (cond
                (vector? ets) ets
                (sequential? ets) (vec ets)
                (string? ets) (or (try-edn ets) (try-json ets) (try-hybrid ets) [])
                :else [])]
      (mapv keywordize-entry-keys (filter map? ets)))))

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
;; MT-7b — the VOCABULARY PROPOSAL path (ADR-0001, CONTEXT.md §Vocabulary &
;; identity). An author meeting an entity type the vocabulary missed does NOT
;; freelance — it declares an explicit proposal `{:type … :uri-keying-fields …
;; :description …}` (keying fields drawn from the REAL sampled columns).
;; Admission is DETERMINISTIC + LOCAL to the container (no shared mutable
;; vocabulary across concurrent ticks); the orchestrator reconciles the
;; containers' admitted proposals post-extract (`reconcile-proposals`).
;; ---------------------------------------------------------------------------

(defn parse-entity-type-proposal
  "Coerce the AUTHOR's optional `:entity-type-proposal` write into a clean map.
   The `:llm` node may deliver a structured map OR — intermittently (the C1
   map-write fragility) — an un-parsed EDN STRING; MIRROR `parse-aggregation-
   spec`: `edn/read-string` a string back to a map. Distinguishes three states
   honestly (#5):
     - nil / blank string        → nil            (NO proposal was made)
     - a map / parseable string  → the map, with `:uri-keying-fields` coerced
                                   to a vector (a bare string/keyword field is
                                   wrapped — the C1 single-value tolerance)
     - garbage (unparseable non-blank string, a non-map) → `::unparseable` —
       a proposal WAS attempted but cannot be read; the caller REJECTS it with
       a reason (never a silent no-proposal).
   Pure + total — never throws."
  [raw]
  (cond
    (map? raw)
    (update raw :uri-keying-fields
            (fn [fs] (cond
                       (vector? fs) fs
                       (sequential? fs) (vec fs)
                       (or (string? fs) (keyword? fs)) [fs]
                       :else fs)))

    (nil? raw) nil

    (string? raw)
    (if (str/blank? raw)
      nil
      (if-let [p (try (let [p (edn/read-string raw)] (when (map? p) p))
                      (catch Throwable _ nil))]
        (parse-entity-type-proposal p)
        ::unparseable))

    :else ::unparseable))

(defn admit-proposal
  "MT-7b — DETERMINISTIC local admission of an author's vocabulary proposal
   against (vocab × the container's REAL sample-rows). Takes the RAW optional
   `:entity-type-proposal` write (map | C1 string | nil). Returns:

     nil — no proposal was made (nil/blank write; the common case).

     {:outcome :admitted :admitted? true :proposed <type>
      :proposal {:type <type> :uri-keying-fields [<field> …] :description <d>}}
       — valid + novel: the caller binds THIS container's drafts against
         `vocab + [proposal]` LOCALLY (no shared mutable state across
         concurrent ticks); the orchestrator reconciles post-extract.

     {:outcome :snapped-to-existing :admitted? false :proposed <type>
      :canonical-type <existing canonical spelling>}
       — the proposal's name collides with the vocabulary (normalized-EXACT,
         `resolve-entity-type` — :type or :aliases): the proposal IS the
         existing type; NO new type is admitted. The snap wins even over bogus
         keying fields — the EXISTING entry's keying fields govern identity.

     {:outcome :rejected :admitted? false :proposed <type|nil> :reason <kw>
      [:missing-fields [<field> …]]}
       — reasons: `:unparseable-proposal` (garbage write), `:blank-type`,
         `:no-keying-fields`, `:keying-field-not-in-sample` (with the fields
         that resolved against NO sampled column). A rejected proposal's
         drafts stay excluded as vocabulary freelancing (the honest 7a path).

   Keying-field validation MIRRORS `recover-via-value`'s matching: a field
   resolves iff its `normalize-name` equals some sample-row key's
   `normalize-name` (case/separator-tolerant EXACT — NO fuzz). An empty sample
   resolves nothing → rejection (never an unvalidated admit). Pure + total."
  [vocab sample-rows raw-proposal]
  (let [parsed (parse-entity-type-proposal raw-proposal)]
    (cond
      (nil? parsed) nil

      (= ::unparseable parsed)
      {:outcome :rejected :admitted? false
       :proposed (when (string? raw-proposal) raw-proposal)
       :reason :unparseable-proposal}

      :else
      (let [{:keys [type uri-keying-fields description]} parsed]
        (cond
          (empty? (str (normalize-name type)))
          {:outcome :rejected :admitted? false :proposed type :reason :blank-type}

          ;; name collision — the proposal IS the existing type (snap, no new type;
          ;; drafts typed with this name already resolve via bind-draft-types).
          (some? (resolve-entity-type vocab type))
          {:outcome :snapped-to-existing :admitted? false :proposed type
           :canonical-type (resolve-entity-type vocab type)}

          (not (and (sequential? uri-keying-fields) (seq uri-keying-fields)))
          {:outcome :rejected :admitted? false :proposed type :reason :no-keying-fields}

          :else
          (let [sample-keys (into #{}
                                  (comp (filter map?)
                                        (mapcat keys)
                                        (keep normalize-name)
                                        (filter seq))
                                  (or sample-rows []))
                missing (vec (remove (fn [f]
                                       (let [nf (normalize-name f)]
                                         (and (seq nf) (contains? sample-keys nf))))
                                     uri-keying-fields))]
            (if (seq missing)
              {:outcome :rejected :admitted? false :proposed type
               :reason :keying-field-not-in-sample :missing-fields missing}
              {:outcome :admitted :admitted? true :proposed type
               :proposal (cond-> {:type type
                                  :uri-keying-fields (vec uri-keying-fields)}
                           description (assoc :description description))})))))))

(defn reconcile-proposals
  "MT-7b — DETERMINISTIC post-extract reconciliation of the containers' ADMITTED
   proposals (pure set logic — NO LLM, NO fuzz). `proposals` is the admitted
   `{:type … :uri-keying-fields … [:description …]}` maps in CONTAINER ORDER
   (concurrent ticks admit LOCALLY; this is where their proposals meet). Returns:

     {:admitted        [<deduped proposal, container order; a name-merged entry
                         carries the variant spellings in :aliases> …]
      :alias-map       {<variant :type spelling> <winning :type spelling> …}
      :merged          <count of proposals that collapsed into an earlier one>
      :requires-review [{:keying-fields [<normalized, sorted> …]
                         :types [<spelling> …] :reason <string>} …]}

   Two rules (ADR-0001):
     1. NORMALIZED-NAME collision (`normalize-name` — the one normalization) →
        MERGED: the FIRST proposal in container order wins entirely (spelling
        AND keying fields — deterministic); each distinct later spelling is
        recorded in the winner's `:aliases` and in `:alias-map` so the
        orchestrator can snap the variant containers' draft `:entity-type`s to
        the winning spelling before canonicalize.
     2. Same normalized KEYING-FIELD SET under DISTINCT names → BOTH KEPT and
        surfaced `:requires-review` — NEVER auto-merged (two distinct types can
        legitimately share a key field, e.g. two junction roles keyed by the
        same id column; auto-merging would silently collapse them — #2/#5. The
        post-landing dedup cascade is the right court). Pure + total."
  [proposals]
  (let [props (vec (or proposals []))
        ;; group by normalized name, first-wins, preserving first-seen order.
        grouped (reduce
                 (fn [acc p]
                   (let [nk (normalize-name (:type p))]
                     (if (contains? (:by-name acc) nk)
                       (update-in acc [:by-name nk :variants] conj p)
                       (-> acc
                           (update :order conj nk)
                           (assoc-in [:by-name nk] {:winner p :variants []})))))
                 {:order [] :by-name {}}
                 props)
        merged (reduce + 0 (map (comp count :variants) (vals (:by-name grouped))))
        admitted (mapv
                  (fn [nk]
                    (let [{:keys [winner variants]} (get-in grouped [:by-name nk])
                          alias-spellings (->> variants
                                               (map :type)
                                               (remove #(= % (:type winner)))
                                               distinct
                                               vec)]
                      (cond-> winner
                        (seq alias-spellings)
                        (update :aliases (fnil into []) alias-spellings))))
                  (:order grouped))
        alias-map (into {}
                        (mapcat (fn [nk]
                                  (let [{:keys [winner variants]} (get-in grouped [:by-name nk])]
                                    (for [v variants
                                          :when (not= (:type v) (:type winner))]
                                      [(:type v) (:type winner)]))))
                        (:order grouped))
        ;; keying-collision detection over the post-merge ADMITTED set.
        keyset (fn [p] (->> (:uri-keying-fields p)
                            (keep normalize-name)
                            (remove empty?)
                            set))
        requires-review (->> admitted
                             (group-by keyset)
                             (keep (fn [[ks ps]]
                                     (when (and (seq ks) (> (count ps) 1))
                                       {:keying-fields (vec (sort ks))
                                        :types (mapv :type ps)
                                        :reason (str "distinct proposed type names share the same "
                                                     "normalized keying-field set — BOTH kept, never "
                                                     "auto-merged (two distinct types can share a key "
                                                     "field); review via the post-landing dedup cascade")})))
                             (sort-by (comp str :types))
                             vec)]
    {:admitted admitted
     :alias-map alias-map
     :merged merged
     :requires-review requires-review}))

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

(defn vocabulary-proposal-guidance
  "MT-7b — the STATIC prompt block for the VOCABULARY PROPOSAL path, appended
   to BOTH extraction-author prompts right after `vocabulary-binding-guidance`
   (the same way the binding block itself is appended). It gives the author the
   ONE legitimate alternative to freelancing when this container's rows are an
   entity type the vocabulary genuinely missed (an unsampled container can
   legitimately hold one — ADR-0001): declare it ONCE as an explicit
   `entity-type-proposal` with keying fields copied from the REAL sampled
   columns. Admission is deterministic at apply time — a keying field that is
   not a real sampled column REJECTS the proposal. Domain-agnostic (#12): it
   names the discipline, never a domain type or column."
  []
  (str
   "\n\n*** VOCABULARY PROPOSAL — when NO vocabulary type fits (read carefully) ***\n"
   "If, after honest inspection, this container's rows are an entity type that "
   "is genuinely NOT in the model-spec's `:entity-types` vocabulary — not a "
   "synonym, case variant, or renaming of an existing `:type` (those MUST reuse "
   "the existing spelling verbatim) — do NOT freelance a type name. Instead "
   "declare the new type ONCE via the `entity-type-proposal` output — a DATA "
   "MAP (not code):\n"
   "  {:type              <the new entity-type name — singular, descriptive>\n"
   "   :uri-keying-fields <the column name(s) that uniquely identify ONE such "
   "entity, copied CHARACTER-FOR-CHARACTER from the REAL sampled columns in "
   "`sample-rows`>\n"
   "   :description       <one self-contained sentence: what one instance of "
   "this type is, in this source>}\n"
   "Then type your concept-drafts (and any `aggregation-spec`) with EXACTLY "
   "that proposed `:type` spelling. The proposal is validated deterministically "
   "at apply time: a keying field that is not a real sampled column REJECTS the "
   "proposal and every draft typed with it is EXCLUDED — so copy the keying "
   "fields from the sample verbatim. When ANY existing vocabulary type fits, "
   "use it and emit NO proposal (leave `entity-type-proposal` empty/absent)."))
