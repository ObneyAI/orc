(ns ai.obney.orc.ontology.core.lints.builtin
  "S10 — Built-in axiom-independent lints (the three the slice ships):

   1. Dangling endpoint   — expressible via :property + :not
   2. Naming convention   — :code escape hatch
   3. Language-tag misuse — :code escape hatch

   These shapes are LITERAL DATA — registering them is just
   `(register-shape! ctx {:ontology-id ... :shape danger-shape})`. The
   `:code` form here uses the function-directly path; consumers
   registering across an event-store round-trip should reference the
   `code-symbol` form (e.g. `'ai.obney.orc.ontology.core.lints.builtin/naming-convention-pred`)
   so the predicate survives serialization.

   Each lint is traceable to a course-verified failure mode (M4 lint
   set, PRD §M4):
   - Dangling endpoint     → relationship integrity (the seedrun would
     drop edges silently otherwise)
   - Naming convention     → standard SHACL cannot express; URI shape
     consistency is a known maintenance trap
   - Language tags         → B15 trap-lint from the grill round-3
     (numeric values tagged with natural-language tags)"
  (:require [clojure.string :as str]))

;; =============================================================================
;; :code predicate functions — must be top-level defns so they're
;; reachable via `requiring-resolve` from the persisted :code-symbol form.
;; =============================================================================

(defn naming-convention-pred
  "Concept URIs must look like `prefix:section:Name` where prefix +
   section are lower-kebab and Name is Capital-start camel-or-kebab.

   This is the canonical built-in :code lint. Returns `nil` for clean,
   `{:violation? true :detail \"...\"}` for violation.

   Pattern locked: `^[a-z][a-z\\-]*:[a-z][a-z\\-]*:[A-Z][A-Za-z0-9\\-]*$`
   Matches: `concept:film:Casablanca`, `failure:Hallucination` (no — only
   2 segments). Reframed: this lint targets only concepts whose scope is
   :custom (matches the workshop ontology section). Built-in URIs like
   `failure:Hallucination` follow a TWO-segment form and are explicitly
   excluded by the target-class scope."
  [{:keys [concept]}]
  (when-not (re-matches #"^[a-z][a-z\-]*:[a-z][a-z\-]*:[A-Z][A-Za-z0-9\-]*$"
                        (str (:uri concept)))
    {:violation? true
     :detail (str "URI " (:uri concept) " fails pattern "
                  "concept-prefix:section:Name")}))

(defn language-tag-misuse-pred
  "A label carrying a non-empty `:lang` whose `:value` is non-linguistic
   (numbers / punctuation only) is the B15 trap.

   This consults the S04-introduced :labels vector ([{:value :lang}]).
   Concepts without :labels never violate (the lint is silent on
   pre-S04 concepts that only have :label)."
  [{:keys [concept]}]
  (let [bad (->> (:labels concept)
                 (filter (fn [{:keys [value lang]}]
                           (and (seq lang)
                                (string? value)
                                (seq (str/trim value))
                                (re-matches #"^[\d\s\-\.,]+$" value))))
                 vec)]
    (when (seq bad)
      {:violation? true
       :detail (str "Non-linguistic label(s) carrying lang tag: "
                    (pr-str (mapv #(select-keys % [:value :lang]) bad)))})))

;; =============================================================================
;; Shape data — what callers register
;; =============================================================================

(def dangling-endpoint-shape
  "Lint 1 — Every concept of :custom scope that has a :skos:related
   edge must have ALL its related-objects resolve to concepts in the
   graph. The :not v1 reading: violate when [object exists] is false.

   Standard-SHACL-expressible (via :property + :not). No :code."
  {:shape/id     :ontology.lint/dangling-endpoint
   :shape/type   :node-shape
   :target-class :custom
   :severity     :violation
   :message      "Relationship endpoint does not resolve to a known concept."
   :deactivated  false
   :property [{:path      "skos:related"
               :min-count 0
               :not       {:object-exists? false}}]})

(def naming-convention-shape
  "Lint 2 — :code escape hatch."
  {:shape/id     :ontology.lint/naming-convention
   :shape/type   :node-shape
   :target-class :custom
   :severity     :violation
   :message      "URI does not follow prefix:section:Name naming convention."
   :deactivated  false
   :code         naming-convention-pred})

(def naming-convention-shape-symbol
  "Persistable variant of the naming-convention shape — references the
   predicate via :code-symbol so the shape body survives EDN serialization
   into the event store. Use when registering through an event store
   round-trip; the in-process form is fine when wiring directly."
  {:shape/id     :ontology.lint/naming-convention
   :shape/type   :node-shape
   :target-class :custom
   :severity     :violation
   :message      "URI does not follow prefix:section:Name naming convention."
   :deactivated  false
   :code-symbol  'ai.obney.orc.ontology.core.lints.builtin/naming-convention-pred})

(def language-tag-misuse-shape
  "Lint 3 — :code escape hatch."
  {:shape/id     :ontology.lint/language-tag-misuse
   :shape/type   :node-shape
   :target-class :custom
   :severity     :warning
   :message      "Label carries a language tag but the value is non-linguistic."
   :deactivated  false
   :code         language-tag-misuse-pred})

(def language-tag-misuse-shape-symbol
  "Persistable variant — see `naming-convention-shape-symbol`."
  {:shape/id     :ontology.lint/language-tag-misuse
   :shape/type   :node-shape
   :target-class :custom
   :severity     :warning
   :message      "Label carries a language tag but the value is non-linguistic."
   :deactivated  false
   :code-symbol  'ai.obney.orc.ontology.core.lints.builtin/language-tag-misuse-pred})

(def all-builtin-shapes
  "The three S10 built-in lints in registration order. Uses
   code-symbol variants so a caller registering all three through an
   event-store-backed `register-shape` command gets shapes that
   round-trip."
  [dangling-endpoint-shape
   naming-convention-shape-symbol
   language-tag-misuse-shape-symbol])
