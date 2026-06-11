(ns ai.obney.orc.ontology.core.task-classifier
  "C-2c-1 + C-2d-2 + R05b: pure classify-task and classify-behaviors functions.

   Wraps `ontology/search-descriptions` with reranker-driven intent
   to assign a tree-class UUID to a task signature. Pure over strings —
   the caller (C-2c-2's executor wedge) handles all upstream lookup
   (sheet → fingerprint → parent-summary) and downstream command
   dispatch.

   C-2d-2 extends classify-task with an optional walk-down phase: when
   top-1 fitness is below :specificity-threshold (default 0.9) AND the
   matched node has children in the tree-class concept graph, the
   classifier descends into the children, picking the one that best
   fits the task. The walk terminates at a confident-enough leaf, an
   un-fittable level (returns the deepest matched ancestor or mints a
   fresh leaf, see below), or a hard depth cap of 5.

   R05b adds classify-behaviors: parallel pure fn that queries the
   Layer-2 behavioral-subtree corpus. Returns top-N few-shot example
   candidates with reasoning; the model decides reuse/adapt/mint.
   When :structural-context is provided, candidates are narrowed via
   behavior:composes-into edges in the concept graph (retrieval hints,
   not gates — empty filter falls back to unfiltered set).

   Distinct from `core/classifier.clj` which is the evaluation-dimension
   classifier (maps low judge scores to failure URIs); this namespace
   is the TASK classifier (maps task signatures to tree-class UUIDs)."
  (:require [clojure.string]
            [malli.core :as m]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp]
            [com.brunobonacci.mulog :as u]))

;; =============================================================================
;; Runtime-resolved dependency
;;
;; `search-descriptions` lives in `ai.obney.orc.ontology.interface`, which
;; also re-exports `classify-task` from this namespace. Requiring it here
;; at compile time would create a cyclic load dep. We resolve the var at
;; call time instead — this still composes cleanly with `with-redefs` in
;; tests because `requiring-resolve` returns the current binding.
;; =============================================================================

(defn- search-descriptions
  [ctx opts]
  ((requiring-resolve 'ai.obney.orc.ontology.interface/search-descriptions)
   ctx opts))

;; =============================================================================
;; Classifier intent (the constant text passed to the reranker)
;; =============================================================================

(def ^:private classifier-intent
  "Text passed as :rerank-with-intent to the reranker. Communicates
   that the caller is doing tree-class assignment so the reranker
   reasons about structural fit rather than free-form relevance."
  (str "I'm classifying a task to find the best-matching tree-class. "
       "Return the candidate whose recommended pattern best fits the "
       "task's structural shape and inputs/outputs. Higher fitness = "
       "better tree-class match."))

(def behavioral-classifier-intent
  "R05b: text passed as :rerank-with-intent when classify-behaviors
   queries the behavioral-subtree corpus. Shipped INTO the reranker
   prompt verbatim. Shared with R05d (consolidator behavioral
   inference) — defined once here, referenced from both call sites.

   Public (no ^:private) so R05d can require + reuse the same constant
   without copying — drift between R05b and R05d would silently change
   the reranker's interpretation of behavioral matches."
  (str "I'm classifying a task to find behavioral subtrees — reusable "
       "competencies (analysis / validation / research / design / etc.) "
       "— that compose into the structural shape this task needs. Return "
       "candidates whose recommended-pattern best fits what the task "
       "ACCOMPLISHES, not just what shape it has. Higher fitness = "
       "better behavioral match."))

;; =============================================================================
;; Opts schema (validated at function entry)
;; =============================================================================

(def ^:private classify-opts-schema
  [:map
   [:task-signature         :string]
   [:parent-summary         {:optional true} [:maybe :string]]
   [:threshold              [:and number? [:>= 0.0] [:<= 1.0]]]
   ;; C-2d-2 walk-down opts. :walk-down? defaults to true at the function
   ;; boundary (handled in the default-merge below). :specificity-threshold
   ;; defaults to 0.9 (top-1 above this is trusted as-is; below triggers walk).
   [:walk-down?             {:optional true} :boolean]
   [:specificity-threshold  {:optional true}
                            [:and number? [:>= 0.0] [:<= 1.0]]]])

;; =============================================================================
;; Walk-down configuration
;; =============================================================================

(def ^:private default-walk-down? true)
(def ^:private default-specificity-threshold 0.9)
(def ^:private max-walk-depth
  "Hard depth cap on walk-down recursion (R-C2d-2 mitigation). At
   this depth we stop walking even if more children would fit and log
   ::walk-down-depth-cap-hit so HITL can surface pathological taxonomies."
  5)

;; =============================================================================
;; Helpers
;; =============================================================================

(defn- build-effective-signature
  "Concatenate the caller's task-signature with the optional parent
   summary. Pure string operation."
  [task-signature parent-summary]
  (if (and parent-summary (pos? (count parent-summary)))
    (str task-signature
         "\n\nPARENT CONTEXT (the surrounding tree this node lives in):\n"
         parent-summary)
    task-signature))

(defn- coerce-to-uuid
  "Normalize a target-id to a UUID:
   - already a UUID → return as-is
   - string that PARSES as a UUID (e.g. round-tripped through JSON) →
     parse it back to the original UUID
   - other string (e.g. fingerprint like 'seed:tree:ChunkedExtraction')
     → derive a stable UUID via nameUUIDFromBytes

   The middle case is critical: ColBERT serializes target-id values
   through JSON, so UUID seeds come back as strings like
   '00000000-c1c1-4001-b001-d0c0a0a0a0a1'. Treating those as opaque
   bytes (hashing) produces a DIFFERENT UUID that doesn't match the
   original seed — the walk-down classifier would return the right
   candidate then lose its identity at the coerce step. Parsing first
   preserves the round-trip."
  [v]
  (cond
    (uuid? v) v
    (string? v) (try (java.util.UUID/fromString v)
                     (catch IllegalArgumentException _
                       (java.util.UUID/nameUUIDFromBytes (.getBytes v "UTF-8"))))
    :else (java.util.UUID/nameUUIDFromBytes (.getBytes (str v) "UTF-8"))))

;; =============================================================================
;; Walk-down helpers (C-2d-2)
;; =============================================================================

(def ^:private tree-class-uri-prefix "tree-class:")

(defn- uri->target-id
  "Strip the 'tree-class:' prefix from a concept URI and parse as UUID
   when possible (matches the URI scheme set up by C-2d-1's reactive
   processor)."
  [uri]
  (let [bare (cond-> uri
               (clojure.string/starts-with? uri tree-class-uri-prefix)
               (subs (count tree-class-uri-prefix)))]
    (try (java.util.UUID/fromString bare)
         (catch Exception _ bare))))

(defn- get-narrower-concepts [ctx uri]
  ((requiring-resolve 'ai.obney.orc.ontology.interface/get-narrower-concepts)
   ctx uri))

(defn- get-description [ctx granularity target-id]
  ((requiring-resolve 'ai.obney.orc.ontology.interface/get-description)
   ctx granularity target-id))

(defn- rerank! [ctx opts]
  ((requiring-resolve 'ai.obney.orc.ontology.core.reranker/rerank!)
   ctx opts))

(defn- get-tree-class-children
  "Return the children of `parent-target-id` as {:target-id :description}
   maps. Reads the concepts read-model for narrower URIs and pulls each
   child's description from the descriptions read-model. Children
   without a description are dropped (the rerank step needs content)."
  [ctx parent-target-id]
  (let [parent-uri (str tree-class-uri-prefix parent-target-id)
        child-uris (or (get-narrower-concepts ctx parent-uri) #{})]
    (vec
      (keep (fn [child-uri]
              (let [child-id (uri->target-id child-uri)
                    desc (get-description ctx :tree-fingerprint child-id)]
                (when desc
                  {:target-id child-id
                   :description desc})))
            child-uris))))

(defn- pick-best-child
  "Re-rank a candidate list of children against the caller's intent.
   Returns {:target-id :fitness-score :reasoning} for the best child
   that meets the auto-classify threshold, or nil if no child does."
  [ctx intent children threshold]
  (when (seq children)
    (let [candidates (mapv (fn [c]
                             {:content (or (-> c :description :summary) "")
                              :score 0.0
                              :document-id (str (:target-id c))
                              :document-metadata {:granularity :tree-fingerprint
                                                  :target-id (:target-id c)
                                                  :confidence 1.0
                                                  :last-update "—"}})
                           children)
          reranked (rerank! ctx {:query intent
                                 :intent intent
                                 :candidates candidates})
          top-1 (first reranked)]
      (when (and top-1 (>= (or (:fitness-score top-1) 0.0) threshold))
        ;; The :document-id is the child's stringified target-id.
        ;; Recover the original target-id from the candidates by lookup
        ;; (avoids re-parsing — the candidates list is the source of truth).
        (let [matching (some (fn [c]
                               (when (= (str (:target-id c)) (:document-id top-1))
                                 c))
                             children)]
          {:target-id (:target-id matching)
           :fitness-score (:fitness-score top-1)
           :reasoning (or (:reasoning top-1) "")})))))

(defn- walk-down-from
  "Descend the tree-class hierarchy from `current` until a leaf is
   reached, the depth cap fires, or no child meets the auto-classify
   threshold.

   Returns {:assigned-tree-id :confidence :reasoning :parent-tree-id
            :was-fresh-mint?}.

   Specificity-threshold is the ENTRY gate (handled by `classify-task`
   before invoking this fn); it does not affect mid-walk behavior. Once
   a walk starts, it continues as long as some child meets the
   auto-classify threshold or the depth cap is reached.

   Algorithm:
   - Depth cap (>= 5): stop with current as the answer; log.
   - Otherwise: look at children.
     - No children OR no child passes auto-classify-threshold:
       - If we walked here (depth > 0): mint fresh leaf under current
         (current is the deepest matched ancestor per C-2d Decision 3).
       - If we never walked (depth = 0): return current as-is — we only
         LOOKED at children, never committed to descending.
     - A child passes: recurse on it at depth+1, carrying current's
       target-id forward as the new parent for the child."
  [ctx intent current depth auto-threshold parent-id]
  (cond
    (>= depth max-walk-depth)
    (do (u/log ::walk-down-depth-cap-hit
               :depth depth
               :target-id (:target-id current))
        {:assigned-tree-id (:target-id current)
         :confidence (:fitness-score current)
         :reasoning (or (:reasoning current) "")
         :parent-tree-id parent-id
         :was-fresh-mint? false})

    :else
    (let [children (get-tree-class-children ctx (:target-id current))]
      (cond
        ;; Genuine leaf: no descendants at all. Return current as-is —
        ;; this is the natural terminating case and is NOT a fresh-mint
        ;; scenario.
        (empty? children)
        {:assigned-tree-id (:target-id current)
         :confidence (:fitness-score current)
         :reasoning (or (:reasoning current) "")
         :parent-tree-id parent-id
         :was-fresh-mint? false}

        :else
        (let [best (pick-best-child ctx intent children auto-threshold)]
          (cond
            (nil? best)
            ;; Children exist but none passed threshold.
            (if (pos? depth)
              ;; We walked here → fresh-mint under current per Decision 3.
              {:assigned-tree-id (random-uuid)
               :confidence (:fitness-score current)
               :reasoning "Walk-down considered children of matched ancestor; none met threshold. Minting fresh leaf under the deepest matched ancestor."
               :parent-tree-id (:target-id current)
               :was-fresh-mint? true}
              ;; Depth 0 — we only LOOKED at children, never committed.
              {:assigned-tree-id (:target-id current)
               :confidence (:fitness-score current)
               :reasoning (or (:reasoning current) "")
               :parent-tree-id parent-id
               :was-fresh-mint? false})

            :else
            (recur ctx intent best (inc depth) auto-threshold
                   (:target-id current))))))))

;; =============================================================================
;; Task signature builder — pure
;; =============================================================================

(defn- format-keys [keys]
  (if (seq keys)
    (clojure.string/join " " (map pr-str keys))
    "(none)"))

(defn- format-tools [tools]
  (if (seq tools)
    (clojure.string/join " " tools)
    "(none)"))

(defn build-task-signature
  "Pure: build the static-config signature string for classification.

   Includes the node's :instruction + :reads keyword names + :writes
   keyword names + :mcp-tools + :browser-tools. Per the C-2c sub-grill
   Decision 6 (Framing B), this is the static slice of the signature;
   the caller concatenates parent-context separately via classify-task's
   :parent-summary opt.

   Designed for repl-researcher nodes. Works for :llm nodes too (no
   :mcp-tools / :browser-tools → '(none)')."
  [node]
  (str "INSTRUCTION:\n"
       (or (:instruction node) "(no instruction)")
       "\n\nREADS: " (format-keys (:reads node))
       "\nWRITES: " (format-keys (:writes node))
       "\nMCP-TOOLS: " (format-tools (:mcp-tools node))
       "\nBROWSER-TOOLS: " (format-tools (:browser-tools node))))

;; =============================================================================
;; Public API
;; =============================================================================

(defn classify-task
  "Pure classification function: given a task signature + optional
   parent-context summary + threshold, returns a tree-class
   assignment (matched corpus entry or fresh UUID mint).

   Args:
     ctx  — context with :event-store / :tenant-id / etc. (passed
            through to search-descriptions)
     opts — {:task-signature :parent-summary :threshold}
       :task-signature  — required string. Caller-built static-config
                          text (instruction + reads + writes + tools).
       :parent-summary  — optional string. Caller-resolved parent-sheet
                          consolidated :summary, or nil/absent when
                          unavailable.
       :threshold       — required double in [0.0, 1.0]. Top-1 below
                          threshold → mint fresh UUID instead of
                          matching.

   Returns:
     {:assigned-tree-id <uuid>    ; matched target or fresh mint
      :confidence       <0.0-1.0> ; top-1 fitness-score (or 0.0 when empty)
      :top-candidates   [{...}]   ; ALL candidates returned by retrieval
      :reasoning        <string>  ; top-1's reasoning, or fresh-mint note
      :was-fresh-mint?  <bool>}   ; false on match; true on mint

   Throws ex-info on invalid opts (e.g. threshold out of range)."
  [ctx opts]
  (when-let [err (m/explain classify-opts-schema opts)]
    (throw (ex-info "Invalid classify-task opts"
                    {:explain err
                     :opts opts})))
  (let [{:keys [task-signature parent-summary threshold
                walk-down? specificity-threshold]
         :or {walk-down? default-walk-down?
              specificity-threshold default-specificity-threshold}} opts
        signature (build-effective-signature task-signature parent-summary)
        candidates (search-descriptions ctx
                     {:query signature
                      :granularity :tree-fingerprint
                      :rerank-with-intent classifier-intent
                      :k 5})
        top-1 (first candidates)
        top-score (or (:fitness-score top-1) 0.0)
        matched? (and top-1 (>= top-score threshold))
        ;; R01: detect reranker fallback by top-1's :rerank-source. When
        ;; apply-rerank fell back to pure ColBERT (reranker threw, returned
        ;; nil, or returned empty), every result entry carries
        ;; :rerank-source :colbert-fallback. Surface that on the result
        ;; so downstream consumers (event body, dashboard, operator
        ;; alerts) can distinguish a legitimate low-confidence match
        ;; from a silent reranker failure that looks identical.
        rerank-fallback? (= :colbert-fallback (:rerank-source top-1))]
    (cond
      ;; No match → legacy fresh-mint at root (no parent)
      (not matched?)
      {:assigned-tree-id (random-uuid)
       :confidence       top-score
       :top-candidates   (vec candidates)
       :reasoning        (or (:reasoning top-1)
                             (if (seq candidates)
                               "Top candidate did not pass confidence threshold; minting fresh task class."
                               "No candidates returned; minting fresh task class."))
       :was-fresh-mint?  true
       :parent-tree-id   nil
       :rerank-fallback? rerank-fallback?}

      ;; Walk-down disabled → legacy match (no walk, no parent)
      (not walk-down?)
      {:assigned-tree-id (coerce-to-uuid
                           (-> top-1 :document-metadata :target-id))
       :confidence       top-score
       :top-candidates   (vec candidates)
       :reasoning        (or (:reasoning top-1) "")
       :was-fresh-mint?  false
       :parent-tree-id   nil
       :rerank-fallback? rerank-fallback?}

      :else
      (let [top-1-id (coerce-to-uuid (-> top-1 :document-metadata :target-id))
            top-1-confident? (>= top-score specificity-threshold)]
        (if top-1-confident?
          ;; High-confidence top-1 → don't walk; return as-is
          {:assigned-tree-id top-1-id
           :confidence       top-score
           :top-candidates   (vec candidates)
           :reasoning        (or (:reasoning top-1) "")
           :was-fresh-mint?  false
           :parent-tree-id   nil
           :rerank-fallback? rerank-fallback?}
          ;; Moderate-confidence top-1 → walk-down to find a tighter
          ;; descendant. walk-down-from carries no parent at depth 0.
          (let [walk-result (walk-down-from ctx classifier-intent
                                            {:target-id top-1-id
                                             :fitness-score top-score
                                             :reasoning (:reasoning top-1)}
                                            0
                                            threshold
                                            nil)]
            (-> walk-result
                (assoc :top-candidates (vec candidates))
                (assoc :rerank-fallback? rerank-fallback?))))))))

;; =============================================================================
;; R05b — classify-behaviors: behavioral subtree retrieval API
;; =============================================================================

(def ^:private default-classify-behaviors-top-n 3)

(def ^:private classify-behaviors-opts-schema
  [:map
   [:task-signature      :string]
   [:threshold           [:and number? [:>= 0.0] [:<= 1.0]]]
   [:structural-context  {:optional true} [:maybe :uuid]]
   [:top-n               {:optional true} :int]])

(defn classify-behaviors
  "R05b: pure behavioral classification. Given a task signature +
   threshold (+ optional :structural-context + :top-n), returns the
   top-N behavioral-subtree examples from the R05a Layer-2 corpus.

   The corpus is FEW-SHOT EXAMPLE MATERIAL for the recursive RLM
   researcher, NOT a routing taxonomy. classify-behaviors returns
   top-N candidates with reasoning + recommended-pattern snippets;
   the model reasons over them and decides reuse/adapt/mint.

   Args:
     ctx  — context with :event-store / :tenant-id / etc. (passed
            through to search-descriptions)
     opts — {:task-signature :threshold :structural-context :top-n}
       :task-signature      — required string. Caller-built static-config
                              text (same shape classify-task receives).
       :threshold           — required double in [0.0, 1.0]. Candidates
                              below this drop out of the result.
       :structural-context  — optional UUID. The structural tree-class
                              already assigned to this task (typically
                              the :assigned-tree-id from a prior
                              classify-task call). When provided, the
                              candidate set is narrowed via the
                              behavior:composes-into graph to behaviors
                              that compose into this shell. RETRIEVAL
                              HINT — when the narrowed set is empty
                              (no Layer-2 behaviors compose into this
                              shell yet), the result FALLS BACK to the
                              unfiltered candidate set so the model
                              still sees relevant examples.
       :top-n               — optional int. Maximum number of behavioral
                              candidates to return above threshold.
                              Defaults to 3.

   Returns:
     {:behaviors        [{:behavior-id <uuid>
                          :confidence <0.0-1.0>
                          :was-fresh-mint? <bool>
                          :reasoning <string>
                          :rerank-source <:reranker | :colbert-fallback>}
                         ...]
      :rerank-fallback? <bool>}

   When NO candidate passes :threshold, :behaviors carries exactly one
   fresh-mint marker with :was-fresh-mint? true + a fresh UUID +
   reasoning string. Per R01: :rerank-fallback? is derived from top-1's
   :rerank-source (true when :colbert-fallback)."
  [ctx opts]
  (when-let [err (m/explain classify-behaviors-opts-schema opts)]
    (throw (ex-info "Invalid classify-behaviors opts"
                    {:explain err
                     :opts opts})))
  (let [{:keys [task-signature threshold top-n structural-context]
         :or {top-n default-classify-behaviors-top-n}} opts
        candidates (search-descriptions ctx
                     {:query task-signature
                      :granularity :behavioral-subtree
                      :rerank-with-intent behavioral-classifier-intent
                      :k (* 2 top-n)})
        ;; R05b + R07: when :structural-context is provided, surface
        ;; behaviors that compose into the shell ALONGSIDE the top
        ;; overall candidates — the composes-into graph is a
        ;; RETRIEVAL HINT (per R05e framing), not a gate.
        ;;
        ;; Previous behavior (R05b only) replaced candidates with the
        ;; composes-into-narrowed set whenever narrowed was non-empty.
        ;; That made composes-into act as a GATE: behaviors that didn't
        ;; compose into the specific shell were dropped, even when they
        ;; were the best fit for the task semantics. R07 live verify on
        ;; code-004-debug-memory-leak surfaced this: Investigation
        ;; (the obvious fit) was filtered out because etl-pipeline's
        ;; :composed-by includes only Extraction + Transformation
        ;; (Investigation composes into the abstract sequential-pipeline
        ;; pattern, not into etl-pipeline specifically).
        ;;
        ;; New behavior: take the UNION of (composes-into matches) +
        ;; (top overall candidates) preserving the reranker's overall
        ;; ranking. The reranker has full reasoning capacity; if a
        ;; behavior is the best semantic fit, it should not be hidden
        ;; from the model because of a missing composes-into edge.
        filtered (if structural-context
                   (let [concepts (rmp/project ctx :ontology/concepts)
                         shell-uri (str "tree-class:" structural-context)
                         composer-uris (get-in concepts [shell-uri :composed-by] #{})
                         composer? (fn [c]
                                     (contains? composer-uris
                                                (str "behavioral-subtree:"
                                                     (-> c :document-metadata :target-id))))
                         composers (filterv composer? candidates)
                         non-composers (filterv (complement composer?) candidates)]
                     ;; Union: composers FIRST (the hint boost), then
                     ;; non-composers in their reranker-given order.
                     ;; distinct preserves first-occurrence so a candidate
                     ;; that's both composer + top-ranked isn't duplicated.
                     (vec (distinct (concat composers non-composers))))
                   candidates)
        top-1 (first filtered)
        rerank-fallback? (and (some? top-1)
                              (= :colbert-fallback (:rerank-source top-1)))
        above-threshold (filter #(let [s (or (:fitness-score %) 0.0)]
                                   (>= s threshold))
                                filtered)
        kept (take top-n above-threshold)]
    (if (seq kept)
      {:behaviors
       (mapv (fn [c]
               {:behavior-id (coerce-to-uuid
                               (-> c :document-metadata :target-id))
                :confidence (or (:fitness-score c) 0.0)
                :was-fresh-mint? false
                :reasoning (or (:reasoning c) "")
                :rerank-source (:rerank-source c)})
             kept)
       :rerank-fallback? rerank-fallback?}
      {:behaviors
       [{:behavior-id (random-uuid)
         :confidence 0.0
         :was-fresh-mint? true
         :reasoning (if (seq candidates)
                      "No candidate above threshold; minting fresh"
                      "No behavioral candidates returned; minting fresh")
         :rerank-source (:rerank-source top-1)}]
       :rerank-fallback? rerank-fallback?})))
