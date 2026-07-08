(ns ai.obney.orc.ontology.core.seeds
  "C-Baseline: loader + dispatcher for the baseline seed corpus that ships
   as EDN resources alongside the ontology component.

   Resource layout (under `components/ontology/resources/seeds/`):
     node-types.edn          — 10 node-type seed bodies
     tree-classes.edn        — 23 structural tree-class seed bodies
                                (each dual-emitted under :tree-fingerprint
                                AND :tree-class scopes)
     behavioral-subtrees.edn — 12 behavioral-subtree seed bodies
                                (the abstract PARENTS, repurposed E3/ADR 0014)
     behavioral-subtree-children.edn — durable coding SUBBEHAVIORS
                                (children) minted via mint-behavioral-subtree
                                with a stable derived id + parent edge (E3)

   The EDN files are generated from `development/src/seed_descriptions.clj`
   via `components/ontology/scripts/regen-seeds.clj`. Editing the EDN by
   hand is supported but the source of truth is the dev Clojure file —
   re-run the regen script to refresh.

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

(defn- emit-behavioral-child!
  "E3 (ADR 0014): emit one DURABLE coding subbehavior via the
   :ontology/mint-behavioral-subtree command. The command owns identity —
   it derives the STABLE id nameUUIDFromBytes(\"mint:\" + name + \":\" +
   parent-behavior), so callers pass :name + :parent-behavior + :body, NOT
   a :target-id. Re-emitting the same (name, parent) lands at the same
   concept (accrues, never scatters). :provenance is :human-authored —
   these are curated bootstrap seeds, not runtime volitional mints."
  [ctx {:keys [name parent-behavior body]}]
  (cp/process-command
    (assoc ctx :command {:command/name :ontology/mint-behavioral-subtree
                         :command/id (random-uuid)
                         :command/timestamp (time/now)
                         :name name
                         :parent-behavior parent-behavior
                         :body body
                         :provenance :human-authored})))

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

   E3 (ADR 0014): the durable coding SUBBEHAVIORS (children) from
   behavioral-subtree-children.edn are then emitted via the
   :ontology/mint-behavioral-subtree command (stable derived id +
   :parent-behavior -> skos:broader), AFTER the abstract behavioral
   parents so the parent concepts exist when the broader edge is drawn.

   Idempotent semantics inherit from the underlying :ontology/record-*
   commands — re-emitting the same target-id appends a new
   :tree-description-updated event with the same body, and the read-model
   projects the latest as `:current` while preserving `:history`."
  [ctx]
  (let [node-types (read-seeds "seeds/node-types.edn")
        tree-classes (read-seeds "seeds/tree-classes.edn")
        behaviorals (read-seeds "seeds/behavioral-subtrees.edn")
        ;; E3 (ADR 0014): durable coding SUBBEHAVIORS (children) under the
        ;; over-matching abstract parents. Emitted via mint-behavioral-subtree
        ;; (stable derived id + :parent-behavior -> skos:broader), AFTER the
        ;; abstract behavioral parents so the parent concepts exist when the
        ;; R05a processor draws the broader edge.
        behavioral-children (read-seeds "seeds/behavioral-subtree-children.edn")]
    (vec
      (concat
        (mapv #(emit-seed! ctx :node-type %) node-types)
        (mapv #(emit-seed! ctx :tree-fingerprint %) tree-classes)
        (mapv #(emit-seed! ctx :tree-class %) tree-classes)
        (mapv #(emit-seed! ctx :tree-fingerprint %) behaviorals)
        (mapv #(emit-behavioral-child! ctx %) behavioral-children)))))

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
   :behavioral-subtree-children (read-seeds "seeds/behavioral-subtree-children.edn")})
