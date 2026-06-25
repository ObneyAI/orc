(ns ai.obney.orc.ontology.core.synthesize-vocab-subbehavior
  "GC-6 — the SYNTHESIZE-VOCAB subbehavior as a delegatable ORC sheet (the keystone).

   ## The problem it closes

   The per-source Model `:llm` node (`model_subbehavior.clj`) names its entity
   `:type`s FREELY from its own profile — `:type` is `:any`, with no controlled
   vocabulary. So source A mints `field_of_study` and source B mints `field` for the
   SAME real entity → GC-1 canonicalizes each from its own type name → two DIFFERENT
   canonical URIs (`fieldofstudy/47` vs `field/47`) → two nodes that EB5 reconcile
   (which keys identity by exact `:uri`) never merges. The graph fragments at the
   source.

   ## What it does (its ONE job) — DISCOVER one shared vocabulary BEFORE minting

   Run AFTER every source is profiled (mirroring `derive-cqs`, which already
   aggregates ALL profiles), this single-turn reasoning step reads the GOAL × the
   FULL set of per-source PROFILEs and DISCOVERS one canonical entity-type
   vocabulary: each canonical entity-type carries the DIFFERENT names the sources
   used for it as `:aliases`, ONE canonical `:type`, ONE canonical
   `:uri-keying-fields` (drawn from a REAL reported column — see below), and a
   self-contained `:description`. The per-source Model then maps its raw entities
   onto this vocabulary (`model_subbehavior.clj` constraint block), so the SAME
   entity resolves to the SAME `(type, keys)` across sources → GC-1 mints ONE URI →
   EB5 merges → the graph connects.

   ## A SINGLE `:llm` node — NOT a repl-researcher (clone of the EB8 derive node)

   Like `derive-cqs`, synthesis is single-turn reasoning over goal + profile(s):
   read, think, emit. NO iterative tool session (Survey already explored). So the
   body is ONE `:llm` node — not a `:repl-researcher`, no recursion. `:reasoning` is
   written FIRST (#13).

   ## C1 — the vocabulary crosses `:delegate` PARSED (the `:llm`-node load-bearing
   structured-schema fix, EB3 carry-forward)

   `:vocabulary` is a MAP that crosses the `:delegate` seam. For an `:llm` node a
   bare `:map` write arrives as a JSON STRING (the executor has no field structure
   to flatten into). So `vocabulary-schema` is a STRUCTURED `[:map …]` wrapping a
   CONCRETE `[:vector [:map …]]` (the C1 outer + per-field fix proven by EB3),
   `{:closed false}` + `:any` leaves tolerant of model variance.

   ## DECIDED (HITL, locked) — canonical key from REAL source columns

   The `:uri-keying-fields` for each canonical entity-type MUST be drawn from the
   identifying-key / column NAMES the profiles actually report for that entity
   (picking the shared/linking one when sources differ) — NEVER an invented name.
   This guarantees GC-1's `recover-via-value` (which normalizes via
   `normalize-key-name`) can always recover the value from the draft's
   `:attributes`, so the URI MINTS instead of silently degrading back into
   fragmentation. The prompt bakes this in explicitly.

   ## Re-orchestration, not rewrite (#8) + domain-agnostic (#12)

   No fork: this clones the EB8 `validate_cq_subbehavior` single-`:llm`-node shape,
   REUSES `normalize-profiles` + `profile-read-schema` (the same tolerant
   profile-vector handling), and stands alongside the existing subbehaviors. NO
   vertical knowledge — the vocabulary is DISCOVERED from the runtime goal × the
   profiles; no domain entity-type / column / format is baked in. `:reasoning`
   FIRST (#13)."
  (:require [ai.obney.orc.orc-service.interface :as dsl]
            [ai.obney.orc.ontology.core.validate-cq-subbehavior :as vcq]
            [clojure.edn :as edn]
            [clojure.string :as str]))

;; =============================================================================
;; The vocabulary OUTPUT contract (the locked schema) — the `:llm` node's :writes
;; =============================================================================

(def vocabulary-key
  "The synthesize-vocab subbehavior's OUTPUT: the ONE canonical entity-type
   vocabulary discovered from the goal × all source profiles. Threaded into the
   per-source Model so each source maps its raw entities onto a SHARED type +
   key vocabulary (so the same entity resolves to the same canonical URI)."
  :vocabulary)

(def vocabulary-schema
  "C1 — the STRUCTURED Malli schema for the `:vocabulary` write (the LOCKED GC-6
   shape, modeled on `model-spec-contract-schema` / `candidate-axioms-schema`). A
   `[:map {:closed false}]` wrapper around a CONCRETE `[:vector [:map …]]` (the C1
   outer-structure + per-field-type fixes so the `:llm` executor parses it into
   real Clojure data, not a JSON string). Leaf values are `:any` (tolerant of model
   variance); `{:closed false}` tolerates extra keys the model adds.

   Each canonical entity-type:
     :type               — the ONE canonical type name (a draft maps onto it).
     :uri-keying-fields  — the canonical key field(s), drawn from REAL reported
                           columns (the locked decision).
     :aliases            — the DIFFERENT names the sources used for this entity.
     :description        — a self-contained description (no file paths / slice
                           codenames / SHAs — the model can't dereference them)."
  [:map {:closed false}
   [:canonical-entity-types {:optional true}
    [:vector [:map {:closed false}
              [:type {:optional true} :any]
              [:uri-keying-fields {:optional true} [:vector :any]]
              [:aliases {:optional true} [:vector :any]]
              [:description {:optional true} :any]]]]])

(defn normalize-vocabulary
  "GC-6 robustness — coerce whatever the `:llm` node emitted for `:vocabulary` into a
   clean `{:canonical-entity-types [<map> …]}`. The `:llm`-node DSCloj parse of a
   `[:vector [:map …]]` field is INTERMITTENT for this model (a pre-existing C1-class
   behavior the model-spec path shares): the SAME write may arrive as parsed Clojure
   data, as an un-parsed EDN STRING, or DOUBLE-NESTED
   (`{:canonical-entity-types {:canonical-entity-types […]}}`). Because GC-6's whole
   purpose is to NOT silently re-fragment, the production threading boundary
   normalizes here so the Model ALWAYS receives a clean vocabulary (never a malformed
   one that drops the constraint). Garbage / nil → an honest empty
   `{:canonical-entity-types []}` (never a throw). Pure + total."
  [v]
  (let [;; whole value as an EDN string → parse it (tolerate a non-parse).
        v (cond-> v (string? v) (#(try (edn/read-string %) (catch Throwable _ nil))))
        ;; the :canonical-entity-types value, unwrapping one level of double-nest
        ;; and parsing a string form.
        cets (when (map? v) (:canonical-entity-types v))
        cets (cond
               (vector? cets) cets
               (sequential? cets) (vec cets)
               ;; double-nested: {:canonical-entity-types {:canonical-entity-types [...]}}
               (map? cets) (let [inner (:canonical-entity-types cets)]
                             (cond (vector? inner) inner
                                   (sequential? inner) (vec inner)
                                   :else []))
               (string? cets) (try (let [p (edn/read-string cets)]
                                     (cond (vector? p) p
                                           (sequential? p) (vec p)
                                           (and (map? p) (sequential? (:canonical-entity-types p)))
                                           (vec (:canonical-entity-types p))
                                           :else []))
                                   (catch Throwable _ []))
               :else [])
        ;; keep only the well-formed entity-type maps (honest — drop garbage entries).
        cets (vec (filter map? cets))]
    {:canonical-entity-types cets}))

;; =============================================================================
;; The synthesis prompt — DISCOVER one canonical vocabulary; #13 reasoning FIRST
;; =============================================================================

(def ^:private runtime-goal-sentinel
  "The synthesis grounds in the goal read at RUNTIME as a `:delegate` :reads input
   (so one sheet serves any goal — the goal is not part of sheet identity)."
  "the GOAL provided to you at runtime as the blackboard input `goal`")

(defn- llm-io-framing
  "Tell the `:llm` node it is a single reasoning step GIVEN the goal + the profile(s)
   as context (no tool session, no tree). Clone of the EB8 framing, adapted: the
   `profile` input may be a single profile map OR a vector of per-source profile
   maps (one per profiled source) — synthesis grounds in ALL of them."
  []
  (str "*** HOW THIS NODE WORKS (read carefully) ***\n"
       "You are a single REASONING step. You are GIVEN two inputs as context: the "
       "GOAL (`goal`, " runtime-goal-sentinel ") and the source PROFILE(S) "
       "(`profile`, the structured output of the earlier Survey step — either ONE "
       "profile map or a VECTOR of per-source profile maps, one per profiled "
       "source). You do NOT call any tools, you do NOT explore the sources, and you "
       "do NOT emit a behavior tree — you THINK over the goal + the profile(s) and "
       "PRODUCE the structured outputs described below. Ignore any general guidance "
       "about tool sessions, `get-input`, `final!`, or `emit-tree!`.\n\n"))

(defn- synthesis-body
  "The core synthesis instruction — DISCOVER one canonical entity-type vocabulary
   from ALL source profiles. Domain-agnostic (#12): it names NO vertical/domain
   entity-type, column, or format — every canonical type, key, alias, and
   description is DISCOVERED from the runtime goal × the profiles."
  []
  (str
   "*** YOUR JOB — DISCOVER ONE SHARED CANONICAL VOCABULARY ***\n"
   "Each profiled source named the entities it found in its OWN words (its "
   "`:entity-candidates`), and identified them by its OWN columns (its "
   "`:identifying-keys` / `:linking-keys`). DIFFERENT sources often name the SAME "
   "real-world entity DIFFERENTLY (e.g. one calls it `record`, another "
   "calls it `entry` — but they are the SAME thing). If each "
   "source's downstream model is left to name entity types freely, the same real "
   "entity gets two different identities and the graph fragments.\n\n"
   "Your job is to read the goal + ALL the profiles and DISCOVER the ONE canonical "
   "set of entity types the sources are collectively about. For each canonical "
   "entity type, decide:\n"
   "  - `type` — ONE canonical name for the entity (the name every source's model "
   "will use for it). Pick a clear, neutral name; this is what makes the SAME "
   "entity byte-identical across sources.\n"
   "  - `aliases` — a VECTOR of the DIFFERENT names the profiles actually used for "
   "this same entity (the raw `:entity-candidate` names from each source, AND any "
   "synonyms a downstream model is likely to use). This is how a source's model "
   "maps its raw entity onto this canonical type — by matching its raw name / "
   "meaning to one of these aliases.\n"
   "  - `uri-keying-fields` — a VECTOR of the identifying-key / column NAME(S) that "
   "key this entity's identity. **CRITICAL (locked): these MUST be drawn from the "
   "identifying-key / column names the profiles ACTUALLY report for this entity "
   "(its `:identifying-keys` / `:linking-keys`). When the sources report DIFFERENT "
   "column names for the SAME key (e.g. `RecordId` vs `RECORD_ID`, or `key_a` "
   "vs `KEY_A`), pick the SHARED / linking one (the one usable to join the "
   "sources). Do NOT invent a new key name and do NOT use a name no profile "
   "reports.** This guarantees the value can be recovered from each source's data "
   "so the canonical URI actually mints (rather than silently degrading back into "
   "fragmentation).\n"
   "  - `description` — a short, SELF-CONTAINED description of what this entity is "
   "and how to recognize it (so a downstream model can match its raw entity to this "
   "canonical type by meaning, not just exact name). Do NOT reference file paths, "
   "internal codenames, or identifiers a reader cannot dereference.\n\n"
   "RULES:\n"
   "  - UNIFY: if two or more sources describe the SAME entity under different "
   "names, produce ONE canonical entity type carrying BOTH (all) names as "
   "`:aliases` and ONE canonical `:uri-keying-fields`.\n"
   "  - DO NOT OVER-MERGE: keep genuinely DISTINCT entities as SEPARATE canonical "
   "types (an entity only one source has is still its own canonical type).\n"
   "  - Ground every canonical type in the profiles — propose no entity type the "
   "profiles do not actually support.\n\n"))

(defn- output-framing
  "Spell out the `:llm` node's declared `:writes` — `:reasoning` FIRST (#13), then
   the `:vocabulary` map under `:canonical-entity-types`."
  []
  (str "*** YOUR OUTPUT — produce these fields, REASONING FIRST (#13) ***\n"
       "  1. `reasoning` — FIRST, before anything else: think through which "
       "entity-candidate names across the profiles refer to the SAME real entity "
       "(so they UNIFY into one canonical type), which are genuinely distinct, and "
       "for each canonical type WHICH reported column name(s) key it (the "
       "shared/linking one when sources differ). Chain-of-thought BEFORE the "
       "vocabulary.\n"
       "  2. `canonical-entity-types` — this is the body of the `vocabulary` you "
       "produce. Emit it as a VECTOR (a list) of entity-type maps — NOT a map, NOT "
       "wrapped again under another `canonical-entity-types` key, just the bare "
       "vector. Each element is a map with `:type` (the canonical name), "
       "`:uri-keying-fields` (a vector of REAL reported column names), `:aliases` (a "
       "vector of the names the sources used), and `:description` (self-contained). "
       "Emit REAL structured Clojure data, NOT a JSON string and NOT prose."))

(defn synthesize-prompt
  "The synthesis node prompt: the I/O framing + the DISCOVER-one-vocabulary body +
   the #13 reasoning-first output framing. Domain-agnostic (#12): the goal is read
   at runtime; no vertical entity-type / column / format is baked in."
  []
  (str (llm-io-framing) (synthesis-body) (output-framing)))

;; =============================================================================
;; The delegatable synthesize-vocab sheet — built on the EB1-EB8 registry pattern
;; =============================================================================

(defn synthesize-vocab-subbehavior-name
  "Canonical registry name for the synthesize-vocab subbehavior. Like derive-cqs it
   bakes in NO source path — it discovers the vocabulary over the GOAL × the
   PROFILE(S) it is handed (all `:reads` inputs), so a SINGLE sheet serves every
   source set. `\"<family>/<behavior>@v<N>\"` — version is part of identity."
  []
  "ontology-synthesize-vocab/synthesize@v1")

(defn synthesize-vocab-sheet-id-for
  "Look up the deterministic sheet-id for the synthesize-vocab subbehavior (pure —
   no event-store read). The central tree points its `:delegate` `:target-sheet-id`
   here without rebuilding the subbehavior."
  []
  (dsl/sheet-id-for-name (synthesize-vocab-subbehavior-name)))

(defn synthesize-vocab-subbehavior-def
  "The synthesize-vocab subbehavior workflow definition.

   Body: a single `:llm` node — single-turn reasoning over goal + profile(s) (clone
   of the EB8 derive node's shape). NOT a `:repl-researcher`.

   Contract (the public `:reads`/`:writes`):
     :reads  [:goal :profile]
     :writes [:reasoning :vocabulary]              (#13 reasoning FIRST)
   The `:vocabulary` MAP write declares the STRUCTURED `vocabulary-schema` — the
   LOAD-BEARING C1 fix for the `:llm` node-type (so it crosses `:delegate` parsed)."
  [{:keys [model]}]
  (let [nm (synthesize-vocab-subbehavior-name)
        mdl (or model "google/gemini-3-flash-preview")]
    (dsl/workflow nm
      (dsl/blackboard
       {;; public :reads — the goal + the per-source profile(s)
        :goal :string
        :profile vcq/profile-read-schema
        ;; public :writes — #13 :reasoning FIRST, then the discovered vocabulary
        :reasoning :string
        vocabulary-key vocabulary-schema})
      (dsl/sequence "synthesize-vocab-root"
        (dsl/llm "synthesize"
          :model mdl
          :instruction (synthesize-prompt)
          :reads [:goal :profile]
          ;; #13 — :reasoning FIRST (chain-of-thought before the vocabulary).
          :writes [:reasoning vocabulary-key])))))

(defn register-synthesize-vocab-subbehavior!
  "REGISTER (build, idempotent) the synthesize-vocab subbehavior sheet and return
   its deterministic sheet-id. Re-registering an unchanged def is a no-op (same id).
   The central evolver tree resolves the name → id via `synthesize-vocab-sheet-id-for`
   and `:delegate`s to it."
  [ctx {:keys [model]}]
  (dsl/build-workflow! ctx (synthesize-vocab-subbehavior-def {:model model})))
