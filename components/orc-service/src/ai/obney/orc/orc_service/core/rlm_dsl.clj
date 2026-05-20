(ns ai.obney.orc.orc-service.core.rlm-dsl
  "Transform S-expr RLM DSL to canonical ORC DSL.

   The RLM (Research Language Model) outputs literal S-expressions representing
   behavior trees. This namespace transforms those to canonical ORC DSL forms
   that can be executed by the sheet service.

   Two-space architecture:
   - Token space: LLM sees previews, outputs S-expr DSL
   - Variable space: Full data available during execution

   The S-expr format is pure data (no functions), making it:
   - Parseable without eval
   - Storable in ontology for learning
   - Safe to transmit and analyze")

(declare rlm-dsl->orc-dsl)

(defn- transform-children
  "Transform a sequence of child nodes."
  [children]
  (map rlm-dsl->orc-dsl children))

(defn- opts-map->keyword-args
  "Convert an options map to a flat keyword argument list.
   {:instruction \"foo\" :reads [:a]} -> [:instruction \"foo\" :reads [:a]]"
  [opts]
  (mapcat (fn [[k v]] [k v]) opts))

(def ^:private default-llm-retry
  "Default retry configuration for LLM nodes.
   3 attempts with exponential-ish backoff: 1s, 2s, 4s"
  {:max-attempts 3
   :backoff-ms [1000 2000 4000]})

(defn- add-default-retry
  "Add default retry config to opts if not already present."
  [opts]
  (if (:retry opts)
    opts  ;; Explicit config takes precedence
    (assoc opts :retry default-llm-retry)))

(defn rlm-dsl->orc-dsl
  "Transform S-expr RLM DSL to canonical ORC DSL.

   Input: [:sequence [:llm {...}] [:final {...}]]
   Output: (sheet/sequence (sheet/llm ...) (final! ...))

   Supported node types:
   - :sequence - Sequential execution
   - :llm - LLM call node
   - :map-each - Parallel iteration
   - :final - Terminal output
   - :chunk-document - Document chunking helper
   - :aggregate - Result aggregation helper
   - :parallel - Parallel execution"
  [tree]
  (when (nil? tree)
    (throw (ex-info "Cannot transform nil tree" {:tree tree})))

  (let [[node-type & args] tree]
    (case node-type
      :sequence
      (let [children (vec args)]
        (apply list 'sheet/sequence (transform-children children)))

      :llm
      (let [opts (-> (first args)
                     (add-default-retry))]
        (apply list 'sheet/llm (opts-map->keyword-args opts)))

      :map-each
      (let [opts (first args)
            child (second args)
            transformed-child (rlm-dsl->orc-dsl child)]
        (apply list 'sheet/map-each (concat (opts-map->keyword-args opts)
                                            [transformed-child])))

      :chunk-document
      (let [{:keys [from size]} (first args)
            output-key (:into (first args))  ;; Don't shadow clojure.core/into
            chunk-fn (fn [{:keys [inputs]}]
                       (let [doc (get inputs from)
                             chunk-size (or size 5000)
                             ;; Properly chunk the string into substrings
                             doc-len (count doc)
                             chunks (vec (loop [i 0 result []]
                                           (if (>= i doc-len)
                                             result
                                             (recur (+ i chunk-size)
                                                    (conj result (subs doc i (min (+ i chunk-size) doc-len)))))))]
                         {output-key chunks}))]
        (list 'sheet/code
              :reads [from]
              :writes [output-key]
              :fn chunk-fn))

      :aggregate
      (let [{:keys [from writes]} (first args)
            ;; Aggregate flattens results from map-each into distinct collections
            ;; If a single write key is declared, wrap the merged result under that key
            ;; Otherwise, use the original keys from the map-each results
            aggregate-fn (fn [{:keys [inputs]}]
                           (let [results (get inputs from)
                                 ;; Merge all result maps, collecting into vectors
                                 merged (reduce (fn [acc result-map]
                                                  (reduce-kv (fn [a k v]
                                                               (update a k (fnil conj []) v))
                                                             acc
                                                             result-map))
                                                {}
                                                results)]
                             ;; If single write key declared and it differs from merged keys,
                             ;; wrap the entire merged result under that key
                             (if (and (= 1 (count writes))
                                      (not (contains? merged (first writes))))
                               {(first writes) merged}
                               ;; Otherwise return merged as-is (keys match)
                               merged)))]
        (list 'sheet/code
              :reads [from]
              :writes writes
              :fn aggregate-fn))

      :parallel
      (let [children (vec args)]
        (apply list 'sheet/parallel (transform-children children)))

      :final
      (let [opts (first args)]
        (list 'final! opts))

      :code
      ;; Model-authored pure-Clojure transform inside a Phase 2 tree.
      ;; :fn may be either:
      ;;   (a) an inline function value, e.g.
      ;;       (fn [{:keys [inputs]}] {:n (count (:chunks inputs))})
      ;;       — registered in the ephemeral-fn-registry by the tree
      ;;       compiler so it survives the child-sheet boundary.
      ;;   (b) a non-empty qualified-symbol string, e.g. "my.ns/transform"
      ;;       — resolved at execution time via ns-resolve.
      ;; Code nodes let the model design transforms (counts, joins, simple
      ;; reductions) without spending sub-LLM tokens on deterministic work.
      (let [{:keys [reads writes] :as opts} (first args)
            fn-ref (:fn opts)]
        (when-not (or (and (string? fn-ref) (seq fn-ref))
                      (fn? fn-ref))
          (throw (ex-info ":code node missing required :fn (qualified-symbol string or inline function)"
                          {:node-type :code :tree tree :opts opts})))
        (list 'sheet/code
              :fn fn-ref
              :reads reads
              :writes writes))

      ;; Default: unknown node type
      (throw (ex-info (str "Unknown node type: " node-type)
                      {:node-type node-type :tree tree})))))
