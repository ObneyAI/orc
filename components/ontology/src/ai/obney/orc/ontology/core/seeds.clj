(ns ai.obney.orc.ontology.core.seeds
  "C-Baseline: loader + dispatcher for the baseline seed corpus that ships
   as EDN resources alongside the ontology component.

   Resource layout (under `components/ontology/resources/seeds/`):
     node-types.edn                   — 10 node-type seed bodies
     tree-classes.edn                 — 23 structural tree-class seed bodies
                                         (each dual-emitted under
                                         :tree-fingerprint AND :tree-class
                                         scopes)
     behavioral-subtrees.edn          — 12 behavioral-subtree seed bodies
     ontology-discovery-patterns.edn  — 5 ontology-discovery patterns
                                         (S18 — AFK-derived from bench
                                         RESULTS; :hitl-status flagged
                                         per entry; behavioral-subtree
                                         scope so classify-behaviors
                                         surfaces them)

   The EDN files are generated from `development/src/seed_descriptions.clj`
   via `components/ontology/scripts/regen-seeds.clj` — except for
   `ontology-discovery-patterns.edn`, which is hand-curated against the
   bench RESULTS and reviewed under the HITL extension surface
   (`components/ontology/resources/seeds/ONTOLOGY-DISCOVERY-HITL.md`).

   Each entry: `{:target-id <uuid-or-keyword-or-string> :body <body-map>}`."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.time.interface :as time]))

(defn- read-seeds
  "Slurp a seed resource from the classpath, parse as EDN, return the vector
   of seed maps. Throws if the resource is missing — that's a build-time
   error, not a runtime fallback."
  [resource-path]
  (if-let [r (io/resource resource-path)]
    (edn/read-string (slurp r))
    (throw (ex-info (str "Seed resource not found on classpath: " resource-path)
                    {:resource resource-path}))))

(defn- emit-seed!
  "Dispatch the appropriate :ontology/record-*-description command for a
   single seed at the given granularity."
  [ctx granularity {:keys [target-id body]}]
  (let [cmd-name (case granularity
                   :node-type        :ontology/record-node-type-description
                   :node-instance    :ontology/record-node-instance-description
                   :tree-fingerprint :ontology/record-tree-description
                   :tree-class       :ontology/record-tree-class-description)]
    (cp/process-command
      (assoc ctx :command {:command/name cmd-name
                           :command/id (random-uuid)
                           :command/timestamp (time/now)
                           :target-id target-id
                           :body body}))))

(defn seed-baseline-corpus!
  "Emit every baseline seed shipped with the ontology component into the
   caller's event store. Returns a vec of command-results.

   Three granularities are emitted:
     :node-type        — 10 entries from node-types.edn
     :tree-fingerprint — 23 structural seeds + 12 behavioral seeds
                         (behavioral bodies carry :scope :behavioral-subtree
                         which routes them to the behavioral-subtree
                         reactive processor)
     :tree-class       — same 23 structural seeds dual-emitted under
                         :tree-class scope so the R-Inject prepend
                         assembler's read path (which keys by tree-class)
                         finds them from bootstrap onward.

   Idempotent semantics inherit from the underlying :ontology/record-*
   commands — re-emitting the same target-id appends a new
   :tree-description-updated event with the same body, and the read-model
   projects the latest as `:current` while preserving `:history`."
  [ctx]
  (let [node-types (read-seeds "seeds/node-types.edn")
        tree-classes (read-seeds "seeds/tree-classes.edn")
        behaviorals (read-seeds "seeds/behavioral-subtrees.edn")
        discovery (read-seeds "seeds/ontology-discovery-patterns.edn")]
    (vec
      (concat
        (mapv #(emit-seed! ctx :node-type %) node-types)
        (mapv #(emit-seed! ctx :tree-fingerprint %) tree-classes)
        (mapv #(emit-seed! ctx :tree-class %) tree-classes)
        (mapv #(emit-seed! ctx :tree-fingerprint %) behaviorals)
        ;; S18 — ontology-discovery patterns route through the
        ;; tree-description command (granularity :tree-fingerprint) so
        ;; classify-behaviors surfaces them. Each body carries
        ;; :scope :behavioral-subtree :discovery-pattern? true and
        ;; :hitl-status :auto-derived (until reviewed per HITL surface).
        (mapv #(emit-seed! ctx :tree-fingerprint %) discovery)))))

(defn baseline-seeds
  "Pure-data query: return the loaded seed catalog as a map of
   {:node-types <vec> :tree-classes <vec> :behavioral-subtrees <vec>}.

   Useful for consumers that want to inspect the corpus without dispatching
   commands (e.g., tests that walk seed bodies for invariants, or tooling
   that diffs the corpus against an app-specific extension)."
  []
  {:node-types (read-seeds "seeds/node-types.edn")
   :tree-classes (read-seeds "seeds/tree-classes.edn")
   :behavioral-subtrees (read-seeds "seeds/behavioral-subtrees.edn")
   :ontology-discovery-patterns (read-seeds "seeds/ontology-discovery-patterns.edn")})

(defn ontology-discovery-patterns
  "S18 — return only the ontology-discovery seeds (pre-filtered by
   `:hitl-status` when `require-hitl-reviewed?` is true).

   Used by `run-discovery!` to retrieve discovery-pattern bodies for
   inclusion in the recursive-RLM session's classify-behaviors surface.

   When `require-hitl-reviewed?` is true, AFK-derived seeds
   (`:hitl-status :auto-derived`) are excluded — only entries
   explicitly marked `:hitl-reviewed` are returned. Default behavior
   (when `nil` or `false`): return ALL patterns.

   Returns a vector of seed entries. Empty vector when nothing meets
   the filter — the caller is responsible for surfacing that case
   in the rlm-trace (the session proceeds with no patterns; this is
   not a crash)."
  ([] (ontology-discovery-patterns false))
  ([require-hitl-reviewed?]
   (let [all (read-seeds "seeds/ontology-discovery-patterns.edn")]
     (if require-hitl-reviewed?
       (filterv #(= :hitl-reviewed (get-in % [:body :hitl-status])) all)
       (vec all)))))
