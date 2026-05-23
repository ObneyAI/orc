(ns ai.obney.orc.trace-logic.core.network
  "Networks of Conscious Agents.

   An ORC sheet is a network of CAs: each leaf node is a CA, each
   composite node is a CA whose kernels compose from its children's
   kernels, each `:delegate` is a sub-network. Networks compose via
   Markov-network composition; a network's aggregate Q and S are the
   relevant kernels for trace-logic operations at the network level.

   For V1, the network is represented as an indexed collection of CAs
   plus the workflow's tree structure. Aggregate kernels at composite
   nodes are computed lazily on demand."
  (:require [ai.obney.orc.trace-logic.core.ca :as ca]
            [ai.obney.orc.trace-logic.core.kernel :as k]
            [ai.obney.orc.trace-logic.core.trace :as trace]))

(defn ->network
  "Build a CA network from a sequence of CAs, indexed by :node-id.

   Optionally include a `:tree` describing the workflow's compositional
   structure (children of each composite). Used by aggregate-kernel
   computation."
  [cas & {:keys [tree]}]
  {:cas-by-node-id (into {} (map (juxt :node-id identity)) cas)
   :tree tree})

(defn get-ca [network node-id]
  (get (:cas-by-node-id network) node-id))

(defn all-cas [network]
  (vals (:cas-by-node-id network)))

(defn aggregate-Q
  "Compose qualia kernels along a path of nodes.

   For a sequence of CAs, returns the kernel obtained by composing
   their Q kernels in order (matrix multiplication). All Q kernels
   must have the same dimension."
  [network node-ids]
  (let [cas (mapv #(get-ca network %) node-ids)
        Qs (mapv ca/qualia-kernel cas)]
    (when (seq Qs)
      (apply k/compose Qs))))

(defn aggregate-S
  "Compose strategy kernels along a path of nodes."
  [network node-ids]
  (let [cas (mapv #(get-ca network %) node-ids)
        Ss (mapv ca/strategy-kernel cas)]
    (when (seq Ss)
      (apply k/compose Ss))))

(defn leq?
  "Network-level CA ordering. Two networks compare iff their aggregate
   Q and S kernels (over the full set of CAs) satisfy `trace/leq?`.

   For networks with different sets of nodes, this is well-defined only
   when the corresponding CAs have matching dimensions."
  [network-A network-B]
  (let [cas-A (all-cas network-A)
        cas-B (all-cas network-B)
        path-A (mapv :node-id cas-A)
        path-B (mapv :node-id cas-B)
        Q-A (aggregate-Q network-A path-A)
        Q-B (aggregate-Q network-B path-B)
        S-A (aggregate-S network-A path-A)
        S-B (aggregate-S network-B path-B)]
    (and (some? Q-A) (some? Q-B)
         (= (count Q-A) (count Q-B))
         (trace/leq? Q-A Q-B)
         (trace/leq? S-A S-B))))
