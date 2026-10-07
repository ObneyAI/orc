(ns ai.obney.orc.evaluation.core.node-version
  "The VERSION of a node: a stable identity of the node's effective definition at
   the moment it executed (PerformanceMonitoring / NodeVersionsDoNotBlend).

   Two executions of one node share a version exactly when the definition that
   ran was the same: same type, executor, instruction (after the run's overrides),
   model, reads, writes, options and the rest of its configuration, under the same
   published sheet version. Runtime bookkeeping (status, last error, tree position,
   the judges attached to it) is not part of the definition: attaching or
   detaching a judge must never fork a node's history."
  (:require [ai.obney.orc.orc-service.interface :as orc])
  (:import (java.security MessageDigest)))

(def ^:private not-definition
  "Keys of a node entry that are bookkeeping or the judging setup, not what the
   node does when it runs."
  #{:id :sheet-id :parent-id :children-ids :status :last-error :judges :name})

(defn- canonical
  "A deterministic, order-independent form of `x`: maps and sets become sorted
   vectors (by printed form), so equal values always print equal."
  [x]
  (cond
    (map? x) (->> x
                  (map (fn [[k v]] [(canonical k) (canonical v)]))
                  (sort-by (comp pr-str first))
                  (into [:map]))
    (set? x) (into [:set] (sort-by pr-str (map canonical x)))
    (sequential? x) (mapv canonical x)
    :else x))

(defn- sha-256-hex [^String s]
  (let [digest (.digest (MessageDigest/getInstance "SHA-256") (.getBytes s "UTF-8"))]
    (apply str (map #(format "%02x" (bit-and % 0xff)) digest))))

(defn effective-definition
  "The definition of `node` as it ran: the node entry less bookkeeping and absent
   fields, with `overrides` (instruction overrides keyed by node name) applied."
  [node overrides]
  (let [overridden (if-let [instruction (and (:name node) (get overrides (:name node)))]
                     (assoc node :instruction instruction)
                     node)]
    (into {} (remove (fn [[k v]] (or (contains? not-definition k) (nil? v)))) overridden)))

(defn version-of
  "The version identifier (a SHA-256 hex string) of `definition` under the
   published sheet `version-number` (nil for a draft run)."
  [definition version-number]
  (sha-256-hex (pr-str (canonical {:definition definition :sheet-version version-number}))))

(defn node-version
  "The version of the node a completion event is for, read from the run it
   belongs to: the tick's own snapshot of the sheet (its nodes, overrides and
   published version) when it has one, else the sheet's live node. nil when the
   node cannot be found."
  [ctx {:keys [sheet-id tick-id node-id]}]
  (let [tick-ctx (orc/get-tick-execution-context ctx tick-id)
        node (if tick-ctx
               (get (:nodes-by-id tick-ctx) node-id)
               (orc/get-node ctx sheet-id node-id))]
    (when node
      (version-of (effective-definition node (:instruction-overrides tick-ctx))
                  (:version-number tick-ctx)))))
