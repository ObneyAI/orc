(ns ai.obney.orc.ontology.core.graph
  "Graph traversal algorithms for ontology-based retrieval.

   Implements:
   - BFS Spreading Activation with exponential decay
   - Link Expansion (fast 2-3 hop traversal)
   - RRF (Reciprocal Rank Fusion) for multi-source result merging
   - Temporal relevance scoring

   Extracted from development/src/graph_search.clj"
  (:require [clojure.string :as str]))

;; =============================================================================
;; Default Edge Weights
;; =============================================================================

(def default-edge-weights
  "Edge weights for different predicate types.
   Higher weight = stronger semantic connection."
  {;; SKOS predicates
   "skos:exactMatch"   1.0
   "skos:closeMatch"   0.95
   "skos:broader"      0.9
   "skos:narrower"     0.85
   "skos:broadMatch"   0.8
   "skos:narrowMatch"  0.8
   "skos:related"      0.7

   ;; RDFS predicates
   "rdfs:subClassOf"   0.9
   "rdfs:seeAlso"      0.6

   ;; OWL predicates
   "owl:sameAs"        1.0
   "owl:equivalentClass" 1.0

   ;; Ontology domain predicates
   "failure:causes"    0.85
   "failure:indicates" 0.8
   "success:enables"   0.85
   "problem:solvedBy"  0.8

   ;; Educational ontology predicates (BRYC/IPEDS)
   "edu:hasCIPCodeEntity"    0.95   ;; Program → CIP code (strong link)
   "edu:mapsToSOC"           0.9    ;; CIP → SOC occupation mapping
   "edu:atInstitution"       0.8    ;; Program → Institution
   "edu:hasOccupation"       0.85   ;; SOC → O*NET occupation
   "edu:relatedProgram"      0.7    ;; Program → related program
   "edu:prerequisiteFor"     0.75   ;; Program prerequisite chain
   "cip:mapsToSOC"           0.9    ;; CIP → SOC crosswalk
   "soc:hasOccupation"       0.85   ;; SOC → occupation detail
   "a"                       0.3    ;; rdf:type - weak for traversal

   ;; Default for unknown predicates
   :default            0.5})

(def default-traversal-config
  "Default configuration for graph traversal."
  {:max-depth       3      ;; Maximum hops from seeds
   :decay           0.5    ;; Activation decay per hop (decay^depth)
   :min-activation  0.01   ;; Stop threshold
   :max-results     100    ;; Result limit
   :bidirectional   true   ;; Follow edges both directions
   :predicates      nil})  ;; nil = follow all predicates

;; =============================================================================
;; Graph Construction from Read Models
;; =============================================================================

(defn concepts->graph
  "Convert concept read model to graph representation.

   Args:
     concepts: Map of URI -> concept-map from concepts read model

   Returns:
     {:nodes #{uri...} :edges {uri [{:to uri :predicate pred :weight w}...]}}

   S07 — `:typed-edges` is consulted in addition to the SKOS buckets.
   When the concept carries a `:typed-edges` map (predicate-string
   → set-of-target-uris) populated by the read-model's default branch
   for non-SKOS predicates (e.g. `\"follows\"`, `\"immediately-follows\"`,
   `\"owl:causes\"`), each entry emits an edge with the ORIGINAL predicate
   name preserved. This is what makes axiom-aware BFS — which filters by
   predicate name — able to honor transitively-marked predicates without
   collapsing them into `:related`. The same edges are ALSO present in
   the `:related` bucket (the read-model populates both views), so the
   default unfiltered BFS still reaches them — preserves the S05
   immediately-follows back-compat path."
  [concepts]
  (let [nodes (atom (set (keys concepts)))
        edges (atom {})]
    (doseq [[uri concept] concepts]
      ;; Add broader edges
      (doseq [broader-uri (:broader concept)]
        (swap! nodes conj broader-uri)
        (swap! edges update uri (fnil conj [])
               {:to broader-uri :predicate "skos:broader" :weight 0.9})
        (swap! edges update broader-uri (fnil conj [])
               {:to uri :predicate "skos:narrower" :weight 0.85 :reverse true}))
      ;; Add narrower edges
      (doseq [narrower-uri (:narrower concept)]
        (swap! nodes conj narrower-uri)
        (swap! edges update uri (fnil conj [])
               {:to narrower-uri :predicate "skos:narrower" :weight 0.85})
        (swap! edges update narrower-uri (fnil conj [])
               {:to uri :predicate "skos:broader" :weight 0.9 :reverse true}))
      ;; Add related edges
      (doseq [related-uri (:related concept)]
        (swap! nodes conj related-uri)
        (swap! edges update uri (fnil conj [])
               {:to related-uri :predicate "skos:related" :weight 0.7})
        (swap! edges update related-uri (fnil conj [])
               {:to uri :predicate "skos:related" :weight 0.7 :reverse true}))
      ;; S07 — typed-edges: preserve the original predicate name on each
      ;; non-SKOS edge so axiom-aware BFS can filter by predicate. The
      ;; default-weight branch handles unknown predicates; SKOS preds
      ;; already had their edges added above (no double-add since they
      ;; don't appear in :typed-edges).
      (doseq [[predicate target-set] (:typed-edges concept)]
        (let [w (get default-edge-weights predicate
                     (get default-edge-weights :default))]
          (doseq [target-uri target-set]
            (swap! nodes conj target-uri)
            (swap! edges update uri (fnil conj [])
                   {:to target-uri :predicate predicate :weight w})
            (swap! edges update target-uri (fnil conj [])
                   {:to uri :predicate predicate :weight w :reverse true})))))
    {:nodes @nodes
     :edges @edges
     :node-count (count @nodes)}))

(defn- parse-turtle-triple
  "Parse a single Turtle triple line into [subject predicate object]."
  [line]
  (when-let [match (re-matches #"^<?([^>\s]+)>?\s+<?([^>\s]+)>?\s+<?([^>\s]+)>?\s*\.?\s*$"
                               (str/trim line))]
    [(second match) (nth match 2) (nth match 3)]))

;; =============================================================================
;; Multi-line Turtle Parser (handles ; continuation syntax)
;; =============================================================================

(defn- extract-uri
  "Extract URI from various Turtle formats.
   Handles: <http://...>, prefix:localname"
  [token]
  (cond
    (nil? token) nil
    (str/blank? token) nil
    ;; Full URI in angle brackets
    (str/starts-with? token "<")
    (-> token (str/replace #"^<" "") (str/replace #">$" ""))
    ;; Already clean
    :else token))

(defn- extract-value
  "Extract value from Turtle object.
   Handles:
   - URIs: <http://...> or prefix:local
   - Literals: \"value\"
   - Language-tagged: \"value\"@en
   - Typed: \"value\"^^xsd:type or 52131.0"
  [token]
  (cond
    (nil? token) nil
    (str/blank? token) nil

    ;; URI in angle brackets
    (str/starts-with? token "<")
    {:type :uri :value (extract-uri token)}

    ;; Prefixed URI (e.g., cip:cip_11_0701)
    (and (re-matches #"^[a-zA-Z][a-zA-Z0-9]*:[^\s\"]+$" token)
         (not (str/starts-with? token "\"")))
    {:type :uri :value token}

    ;; Quoted literal with language tag: "value"@en
    (re-matches #"^\".*\"@[a-z]+$" token)
    (let [[_ val lang] (re-matches #"^\"(.*)\"@([a-z]+)$" token)]
      {:type :literal :value val :lang lang})

    ;; Quoted literal with datatype: "value"^^xsd:type
    (re-matches #"^\".*\"\^\^.*$" token)
    (let [[_ val dtype] (re-matches #"^\"(.*)\"\^\^(.+)$" token)]
      {:type :typed-literal :value val :datatype dtype})

    ;; Simple quoted literal: "value"
    (re-matches #"^\".*\"$" token)
    {:type :literal :value (subs token 1 (dec (count token)))}

    ;; Numeric literal
    (re-matches #"^-?[0-9]+\.?[0-9]*$" token)
    {:type :number :value (try (Double/parseDouble token) (catch Exception _ token))}

    ;; Boolean
    (contains? #{"true" "false"} (str/lower-case token))
    {:type :boolean :value (= "true" (str/lower-case token))}

    ;; Fallback - treat as URI
    :else
    {:type :uri :value token}))

(defn- tokenize-turtle-line
  "Tokenize a Turtle line handling quoted strings.
   Returns vector of tokens."
  [line]
  (let [line (str/trim line)]
    (when-not (str/blank? line)
      (loop [chars (seq line)
             tokens []
             current ""
             in-quotes false
             in-uri false]
        (if (empty? chars)
          (if (str/blank? current)
            tokens
            (conj tokens current))
          (let [c (first chars)]
            (cond
              ;; Start of quoted string
              (and (= c \") (not in-quotes))
              (recur (rest chars) tokens (str current c) true in-uri)

              ;; End of quoted string (but might have @lang or ^^type following)
              (and (= c \") in-quotes)
              (recur (rest chars) tokens (str current c) false in-uri)

              ;; Start of URI
              (and (= c \<) (not in-quotes))
              (recur (rest chars) tokens (str current c) in-quotes true)

              ;; End of URI
              (and (= c \>) (not in-quotes))
              (recur (rest chars) tokens (str current c) in-quotes false)

              ;; Whitespace outside quotes - token separator
              (and (Character/isWhitespace c) (not in-quotes) (not in-uri))
              (if (str/blank? current)
                (recur (rest chars) tokens "" in-quotes in-uri)
                (recur (rest chars) (conj tokens current) "" in-quotes in-uri))

              ;; Any other character
              :else
              (recur (rest chars) tokens (str current c) in-quotes in-uri))))))))

(defn parse-turtle-multiline
  "Parse Turtle with multi-line support (handles ; continuation syntax).

   Handles real-world Turtle files with:
   - @prefix declarations (stored but not expanded)
   - Semicolon (;) continuation - same subject, new predicate-object
   - Comma (,) continuation - same subject and predicate, new object
   - Period (.) termination - end of statement
   - Literal values with @en language tags
   - Typed literals with ^^xsd:type
   - Numeric literals

   Returns:
     {:nodes #{uri...}
      :edges {uri [{:to uri :predicate pred :weight w :value val}...]}
      :prefixes {\"prefix\" \"expansion\"}
      :properties {uri {:predicate value ...}}
      :triple-count N}

   Usage:
     (parse-turtle-multiline (slurp \"data.ttl\"))"
  [turtle-str]
  (let [lines (str/split-lines (or turtle-str ""))
        prefixes (atom {})
        nodes (atom #{})
        edges (atom {})
        properties (atom {})  ;; Store literal properties
        triple-count (atom 0)

        ;; State for multi-line parsing
        current-subject (atom nil)
        current-predicate (atom nil)]

    ;; Process each line
    (doseq [line lines]
      (let [trimmed (str/trim line)]
        (cond
          ;; Skip empty lines and comments
          (or (str/blank? trimmed)
              (str/starts-with? trimmed "#"))
          nil

          ;; Parse @prefix declaration
          (str/starts-with? trimmed "@prefix")
          (when-let [[_ prefix expansion]
                     (re-matches #"@prefix\s+([^:]+):\s+<([^>]+)>\s*\.$" trimmed)]
            (swap! prefixes assoc prefix expansion))

          ;; Parse @base declaration
          (str/starts-with? trimmed "@base")
          nil  ;; Skip for now

          ;; Parse statement line
          :else
          (let [tokens (tokenize-turtle-line trimmed)
                ;; Detect line ending
                ends-with-period (str/ends-with? trimmed ".")
                ends-with-semi (str/ends-with? trimmed ";")
                ends-with-comma (str/ends-with? trimmed ",")
                ;; Remove trailing punctuation from tokens
                tokens (cond-> tokens
                         (and (seq tokens)
                              (contains? #{"." ";" ","} (last tokens)))
                         (subvec 0 (dec (count tokens))))]

            (when (seq tokens)
              (cond
                ;; Full triple: subject predicate object
                (and (nil? @current-subject) (>= (count tokens) 3))
                (let [[subj pred & objs] tokens
                      subj-uri (extract-uri subj)]
                  (reset! current-subject subj-uri)
                  (reset! current-predicate pred)
                  (swap! nodes conj subj-uri)
                  (doseq [obj objs]
                    (let [obj-val (extract-value obj)]
                      (when obj-val
                        (swap! triple-count inc)
                        (if (= :uri (:type obj-val))
                          ;; URI object - create edge
                          (let [obj-uri (:value obj-val)
                                weight (get default-edge-weights pred
                                            (get default-edge-weights :default))]
                            (swap! nodes conj obj-uri)
                            (swap! edges update subj-uri (fnil conj [])
                                   {:to obj-uri :predicate pred :weight weight})
                            ;; Bidirectional edge
                            (swap! edges update obj-uri (fnil conj [])
                                   {:to subj-uri :predicate pred :weight weight :reverse true}))
                          ;; Literal object - store as property
                          (swap! properties update subj-uri assoc
                                 (keyword pred) (:value obj-val)))))))

                ;; Continuation with new predicate: predicate object (after ;)
                (and @current-subject (>= (count tokens) 2))
                (let [[pred & objs] tokens]
                  (reset! current-predicate pred)
                  (doseq [obj objs]
                    (let [obj-val (extract-value obj)]
                      (when obj-val
                        (swap! triple-count inc)
                        (if (= :uri (:type obj-val))
                          (let [obj-uri (:value obj-val)
                                weight (get default-edge-weights pred
                                            (get default-edge-weights :default))]
                            (swap! nodes conj obj-uri)
                            (swap! edges update @current-subject (fnil conj [])
                                   {:to obj-uri :predicate pred :weight weight})
                            (swap! edges update obj-uri (fnil conj [])
                                   {:to @current-subject :predicate pred :weight weight :reverse true}))
                          (swap! properties update @current-subject assoc
                                 (keyword pred) (:value obj-val)))))))

                ;; Continuation with same predicate: object (after ,)
                (and @current-subject @current-predicate (= (count tokens) 1))
                (let [obj (first tokens)
                      obj-val (extract-value obj)]
                  (when obj-val
                    (swap! triple-count inc)
                    (if (= :uri (:type obj-val))
                      (let [obj-uri (:value obj-val)
                            pred @current-predicate
                            weight (get default-edge-weights pred
                                        (get default-edge-weights :default))]
                        (swap! nodes conj obj-uri)
                        (swap! edges update @current-subject (fnil conj [])
                               {:to obj-uri :predicate pred :weight weight})
                        (swap! edges update obj-uri (fnil conj [])
                               {:to @current-subject :predicate pred :weight weight :reverse true}))
                      (swap! properties update @current-subject update
                             (keyword @current-predicate)
                             (fn [v]
                               (if (vector? v)
                                 (conj v (:value obj-val))
                                 (if v [v (:value obj-val)] (:value obj-val))))))))))

            ;; Update state based on line ending
            (when ends-with-period
              (reset! current-subject nil)
              (reset! current-predicate nil))
            (when ends-with-comma
              ;; Keep same subject and predicate
              nil)))))

    {:nodes @nodes
     :edges @edges
     :prefixes @prefixes
     :properties @properties
     :triple-count @triple-count
     :node-count (count @nodes)}))

(defn parse-turtle-graph
  "Parse Turtle string into graph representation.
   Returns {:nodes #{uri...} :edges {uri [{:to uri :predicate pred :weight w}...]}}"
  [turtle-str]
  (let [lines (str/split-lines (or turtle-str ""))
        triples (->> lines
                     (remove #(str/starts-with? (str/trim %) "@prefix"))
                     (remove #(str/starts-with? (str/trim %) "#"))
                     (remove str/blank?)
                     (map parse-turtle-triple)
                     (remove nil?))
        nodes (atom #{})
        edges (atom {})]
    (doseq [[subj pred obj] triples]
      (swap! nodes conj subj)
      (swap! nodes conj obj)
      (let [weight (get default-edge-weights pred
                        (get default-edge-weights :default))]
        (swap! edges update subj (fnil conj [])
               {:to obj :predicate pred :weight weight})
        ;; Add reverse edge for bidirectional traversal
        (swap! edges update obj (fnil conj [])
               {:to subj :predicate pred :weight weight :reverse true})))
    {:nodes @nodes
     :edges @edges
     :triple-count (count triples)}))

;; =============================================================================
;; BFS Spreading Activation
;; =============================================================================

(defn bfs-spreading-activation
  "BFS traversal with exponential decay activation scores.

   Algorithm:
   1. Initialize seeds with activation 1.0
   2. Process nodes level by level (BFS)
   3. For each node, propagate to neighbors:
      neighbor_activation = current_activation × edge_weight × decay^depth
   4. Stop when activation < min_activation or depth > max_depth
   5. Return all visited nodes sorted by activation score

   Args:
     graph: Graph map with :nodes and :edges
     seed-uris: Collection of starting URIs
     config: Optional traversal config map

   Returns:
     Vector of {:uri :score :path :depth :source-seed :edge-type}"
  [graph seed-uris & [config]]
  (let [{:keys [max-depth decay min-activation max-results bidirectional predicates]
         :or {max-depth 3 decay 0.5 min-activation 0.01 max-results 100 bidirectional true}}
        (merge default-traversal-config config)

        edges (:edges graph)
        seed-set (set seed-uris)

        ;; Activation scores and paths for each node
        activations (atom {})
        paths (atom {})

        ;; BFS queue: [[uri current-activation depth source-seed path]]
        queue (atom (into clojure.lang.PersistentQueue/EMPTY
                          (for [seed seed-uris]
                            [seed 1.0 0 seed [seed]])))]

    ;; Initialize seeds
    (doseq [seed seed-uris]
      (swap! activations assoc seed 1.0)
      (swap! paths assoc seed {:path [seed] :depth 0 :source-seed seed :edge-type nil}))

    ;; BFS traversal
    (loop []
      (when-let [item (first @queue)]
        (swap! queue pop)
        (let [[uri activation depth source-seed path] item]
          (when (< depth max-depth)
            (let [neighbors (get edges uri [])]
              (doseq [{:keys [to predicate weight reverse]} neighbors
                      :when (or (nil? predicates)
                                (contains? (set predicates) predicate))
                      :when (or bidirectional (not reverse))]
                (let [new-activation (* activation weight (Math/pow decay depth))
                      current-activation (get @activations to 0.0)]
                  (when (and (> new-activation min-activation)
                             (> new-activation current-activation))
                    (swap! activations assoc to new-activation)
                    (swap! paths assoc to {:path (conj path to)
                                           :depth (inc depth)
                                           :source-seed source-seed
                                           :edge-type predicate})
                    (swap! queue conj [to new-activation (inc depth) source-seed (conj path to)]))))))
          (recur))))

    ;; Build results sorted by activation
    (->> @activations
         (map (fn [[uri score]]
                (let [{:keys [path depth source-seed edge-type]} (get @paths uri)]
                  {:uri uri
                   :score score
                   :path (or path [uri])
                   :depth (or depth 0)
                   :source-seed source-seed
                   :edge-type edge-type})))
         (sort-by :score >)
         (take max-results)
         vec)))

;; =============================================================================
;; Link Expansion (Fast 2-3 hop)
;; =============================================================================

(defn link-expansion
  "Fast 2-3 hop expansion for real-time suggestions.

   Complexity: O(S × D) where S = seeds, D = avg degree
   Use for: autocomplete, quick suggestions, low-latency scenarios

   Args:
     graph: Graph map with :nodes and :edges
     seed-uris: Collection of starting URIs
     opts: {:hops N} - Number of expansion hops (1-3)

   Returns:
     Vector of {:uri :score :path :depth}"
  [graph seed-uris & {:keys [hops] :or {hops 2}}]
  (let [hops (min 3 (max 1 hops))
        edges (:edges graph)
        seed-set (set seed-uris)

        ;; Track visited with scores
        visited (atom (into {} (for [s seed-uris] [s {:score 1.0 :path [s] :depth 0}])))

        ;; Current frontier
        frontier (atom seed-set)]

    ;; Expand for each hop
    (doseq [hop (range 1 (inc hops))]
      (let [new-frontier (atom #{})]
        (doseq [uri @frontier]
          (let [current-score (get-in @visited [uri :score] 1.0)
                current-path (get-in @visited [uri :path] [uri])
                neighbors (get edges uri [])]
            (doseq [{:keys [to weight]} neighbors]
              (let [new-score (* current-score weight (/ 1.0 hop))
                    existing-score (get-in @visited [to :score] 0)]
                (when (> new-score existing-score)
                  (swap! visited assoc to {:score new-score
                                           :path (conj current-path to)
                                           :depth hop})
                  (swap! new-frontier conj to))))))
        (reset! frontier @new-frontier)))

    ;; Convert to result format
    (->> @visited
         (map (fn [[uri {:keys [score path depth]}]]
                {:uri uri
                 :score score
                 :path path
                 :depth depth}))
         (sort-by :score >)
         vec)))

;; =============================================================================
;; RRF (Reciprocal Rank Fusion)
;; =============================================================================

(defn compute-rrf-scores
  "Reciprocal Rank Fusion for merging multiple ranked result lists.

   Formula: score(d) = sum(1 / (k + rank_i))
   where k = 60 (per original RRF paper)

   Args:
     batches: Vector of result batches, each batch is vector of {:uri :score}
     opts: {:k N} - RRF constant (default 60)

   Returns:
     Vector of [uri fused-score] sorted by fused score descending"
  [batches & {:keys [k] :or {k 60}}]
  (let [;; Convert each batch to ranked list
        ranked-batches (for [batch batches]
                         (->> batch
                              (sort-by :score >)
                              (map-indexed (fn [rank item] [(:uri item) rank]))))

        ;; Accumulate RRF scores
        rrf-scores (reduce
                     (fn [acc ranked-list]
                       (reduce (fn [a [uri rank]]
                                 (update a uri (fnil + 0.0)
                                         (/ 1.0 (+ k (inc rank)))))
                               acc
                               ranked-list))
                     {}
                     ranked-batches)]

    ;; Sort by fused score
    (->> rrf-scores
         (sort-by val >)
         vec)))

(defn merge-batches
  "Merge multiple search batches using RRF or weighted-sum fusion.

   Args:
     batches: Vector of result batches
     opts: {:method \"rrf\"/\"weighted-sum\" :weights [...]}

   Returns:
     Vector of {:uri :score :sources}"
  [batches & {:keys [method weights] :or {method "rrf"}}]
  (case method
    "rrf" (let [rrf-results (compute-rrf-scores batches)]
            (mapv (fn [[uri score]]
                    {:uri uri
                     :score score
                     :sources (count (filter #(some (fn [r] (= (:uri r) uri)) %) batches))})
                  rrf-results))

    "weighted-sum"
    (let [weights (or weights (repeat (count batches) 1.0))
          uri-scores (atom {})]
      (doseq [[batch weight] (map vector batches weights)
              result batch]
        (swap! uri-scores update (:uri result)
               (fnil + 0.0) (* (:score result) weight)))
      (->> @uri-scores
           (sort-by val >)
           (mapv (fn [[uri score]]
                   {:uri uri :score score :sources 1}))))))

;; =============================================================================
;; Temporal Relevance Scoring
;; =============================================================================

(defn compute-temporal-relevance
  "Compute temporal relevance score.

   Returns:
   - 1.0 for current year
   - 1.1 for future years (slight boost)
   - Decaying score for past years (min 0.1)
   - 0.5 for entities without temporal info"
  [entity-year reference-year & {:keys [decay-per-year] :or {decay-per-year 0.15}}]
  (cond
    (nil? entity-year) 0.5
    (= entity-year reference-year) 1.0
    (> entity-year reference-year) 1.1
    :else (max 0.1 (- 1.0 (* (- reference-year entity-year) decay-per-year)))))

(defn apply-temporal-scoring
  "Apply temporal relevance scoring to search results.

   Final score = graph_score × temporal_relevance

   Args:
     results: Vector of {:uri :score ...}
     temporal-entities: Map of uri -> effective-year
     reference-year: Current year for relevance calculation

   Returns:
     Results with adjusted scores"
  [results temporal-entities reference-year]
  (mapv (fn [result]
          (let [uri (:uri result)
                year (get temporal-entities uri)
                temporal-score (compute-temporal-relevance year reference-year)]
            (assoc result
                   :temporal-relevance temporal-score
                   :score (* (:score result) temporal-score))))
        results))

;; =============================================================================
;; Convenience Functions
;; =============================================================================

(defn quick-search
  "Quick graph search without full ORC workflow.

   Args:
     graph: Graph map or concept map
     seed-uris: Vector of seed URIs
     opts: {:strategy \"bfs\"/\"link_expansion\" :max-depth N :decay N :hops N}

   Returns:
     Vector of {:uri :score :path :depth}"
  [graph seed-uris & {:keys [strategy max-depth decay hops]
                      :or {strategy "bfs" max-depth 3 decay 0.5 hops 2}}]
  (let [;; Convert concept map to graph if needed
        g (if (contains? graph :edges)
            graph
            (concepts->graph graph))
        config {:max-depth max-depth :decay decay}]
    (case strategy
      "bfs" (bfs-spreading-activation g seed-uris config)
      "link_expansion" (link-expansion g seed-uris :hops hops)
      (bfs-spreading-activation g seed-uris config))))

(defn expand-from-concept
  "Expand neighborhood from a single concept URI.
   Convenience wrapper around BFS."
  [graph uri & {:keys [max-depth decay] :or {max-depth 2 decay 0.6}}]
  (bfs-spreading-activation graph [uri] {:max-depth max-depth :decay decay}))
