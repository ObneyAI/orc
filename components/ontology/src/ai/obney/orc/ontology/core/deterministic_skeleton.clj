(ns ai.obney.orc.ontology.core.deterministic-skeleton
  "S17 — deterministic skeleton builder.

   The hand-authored half of the M8 hybrid: a hand-authored pipeline
   owns the SUBSTRATE CONTRACTS, and discovery (when added in S18)
   plugs in as a recursive-RLM layer on top of THIS namespace.

   ## Pipeline ordering (load-bearing)

     parse → normalize → dedup → validate → embed → index → exit-criterion

   Each stage is invoked through the predecessor slice's public
   commands and read-models — nothing here writes events directly.

   - **parse** delegates to S09 (TTL ingest) or a caller-supplied
     adapter for the source's format. Concepts + relationships +
     metadata events land in the event store HERE.
   - **normalize** is a verify-and-summarize stage when parse used
     TTL ingest (the bundle features S04+S05+S06+S07 are already
     emitted by the ingester). For non-TTL adapters this stage is
     where label normalization, quantity coercion, edge metadata
     stamping, and characteristic-axiom emission would land — kept
     deliberately decoupled so the discovery seam can plug a model-
     authored normalizer in later.
   - **dedup** runs S12's tiered cascade across candidate pairs in
     scope. The check-before-mint hook fires inside S12's command.
     S13 evidence aggregation emits AUTOMATICALLY per side; this
     skeleton does NOT touch evidence — it just calls the cascade.
   - **validate** registers the configured shape symbols + runs the
     S10/S11 lint registry. Severity `:violation` HALTS the pipeline
     (configurable via `:validation {:halt-on ...}`). `:warning` /
     `:info` are collected in `:validation-warnings` and the build
     proceeds.
   - **embed** invokes a caller-supplied embedding fn. Default: skip
     with `:skipped? true`. Production wires `embedding/embed-
     concepts-batch!`.
   - **index** invokes a caller-supplied reindex fn. Default: skip.
   - **exit-criterion** reads the stored ORSD spec via S14 and runs
     the S15 CQ runner. The gate compares `:pass-rate` and
     `:unknown-rate` against the configured thresholds. Failure
     surfaces as `:status :failed-cq` with the full graph-health
     attached — events that DID land (concepts, equivalences) stay;
     events are facts.

   ## Failure-shape discipline

   Every stage returns either `{:status :ok ...}` or a structured
   `{:status :failed :stage <kw> :error <root-cause map>}`. The
   pipeline driver translates `:failed` outcomes into a top-level
   `:status :failed-at-<stage>` carrying the inner `:error` verbatim
   — no try/catch swallows root causes. Lint violations and CQ
   failures are distinct failure KINDS with their own status
   keywords (`:failed-validation`, `:failed-cq`) so callers can
   branch on the root-cause CATEGORY without parsing message strings.

   ## What this namespace does NOT do

   - Discovery — that's S18's recursive-RLM seam. This skeleton
     accepts pre-shaped sources (TTL strings, or whatever the
     caller's adapter produces). S18 will wrap this build! and
     wire the discovery phase ahead of parse.
   - Old-sheet replacement — the existing sheets keep working
     unchanged. The skeleton coexists; deprecation is Phase-4 work.
   - Schema validation of the source content — events are validated
     by Grain's event-store at append time. A schema-violating event
     surfaces as a parse-stage failure with the anomaly attached."
  (:require [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp]
            [ai.obney.grain.time.interface :as time]
            [ai.obney.orc.ontology.interface :as ontology]
            [ai.obney.orc.ontology.core.read-models :as rm]
            [ai.obney.orc.ontology.core.dedup-cascade :as dedup]
            [ai.obney.orc.ontology.core.lints.queries :as lint-q]
            [ai.obney.orc.ontology.core.ttl-ingest :as ttl-ingest]
            [ai.obney.orc.ontology.core.serialization :as serialization]
            [ai.obney.orc.ontology.core.field-analyzer :as field-analyzer]
            [ai.obney.orc.ontology.core.colbert-indexer :as colbert-indexer]
            [ai.obney.orc.ontology.core.embedding :as embedding]
            [ai.obney.grain.event-store-v3.interface :as es]
            [clojure.set :as set]
            [clojure.string :as str]))

;; =============================================================================
;; Per-stage helpers (private — stages are invoked through `build!` only)
;; =============================================================================

(defn- ms-since [start] (- (System/currentTimeMillis) start))

(defn- parse-stage
  "Delegate parsing to the source-specific adapter.

   `sources` is a vector of `{:type :ttl|:inline-concepts :content ...}`
   maps. New types are added by extending `process-source` below — the
   per-type dispatch keeps the stage signature stable so the discovery
   seam (S18) can layer in a model-authored adapter.

   Returns either:
     {:status :ok :sources-parsed <int> :reports [...] :stage-duration-ms <int>}
   or
     {:status :failed :stage :parse :error {:source <map> :anomaly <map>}}."
  [ctx {:keys [ontology-id sources]}]
  (let [start (System/currentTimeMillis)]
    (loop [remaining sources
           reports []]
      (if (empty? remaining)
        {:status :ok
         :sources-parsed (count sources)
         :reports reports
         :stage-duration-ms (ms-since start)}
        (let [source (first remaining)
              report
              (case (:type source)
                :ttl
                (ttl-ingest/ingest-ttl! ctx (:content source)
                                        {:ontology-id ontology-id})

                :inline-concepts
                (do
                  (doseq [c (:concepts source)]
                    (cp/process-command
                     (assoc ctx :command
                            (merge {:command/name :ontology/create-concept
                                    :command/id (random-uuid)
                                    :command/timestamp (time/now)
                                    :ontology-id ontology-id
                                    :scope :custom
                                    :broader []
                                    :indicators []}
                                   c))))
                  {:ingested? true
                   :ontology-id ontology-id
                   :counts {:concept (count (:concepts source))}})

                :inline-relationships
                (do
                  (doseq [r (:relationships source)]
                    (cp/process-command
                     (assoc ctx :command
                            (merge {:command/name :ontology/create-relationship
                                    :command/id (random-uuid)
                                    :command/timestamp (time/now)
                                    :ontology-id ontology-id
                                    :confidence-class :extracted
                                    :properties {}}
                                   r))))
                  {:ingested? true
                   :ontology-id ontology-id
                   :counts {:relationship (count (:relationships source))}})

                ;; V06 — RAW STRUCTURED SOURCE route. The deterministic skeleton
                ;; WRAPS the LLM discovery here (Discipline #10): given a source
                ;; descriptor for a csv / sql / excel file (and the discovery
                ;; ontology-id + any model/budget knobs), it runs the
                ;; RLM-controlled, format-dispatched exploration, then emits the
                ;; resulting concept/relationship/axiom drafts as events through
                ;; the existing ontology commands — feeding the SAME substrate as
                ;; the :inline-* seams. run-discovery! + compile-discovery-source!
                ;; are lazy-resolved (they live in the discovery namespace, which
                ;; requiring-resolves back into this skeleton — a static require
                ;; would be circular).
                ;;
                ;; Source shape:
                ;;   {:type :rlm-discovery
                ;;    :source-descriptor {:name <kw> :type :csv|:sql|:excel
                ;;                        :path <str>}     ; OR a :text source
                ;;    :discovery {:model <str> :budget <map>
                ;;                :auto-classify? <bool>
                ;;                :require-hitl-reviewed-patterns? <bool>
                ;;                :discovery-prompt <str> :debug? <bool>}}  ; opts
                ;;
                ;; A discovery session that fails (:failed-at-session) or yields
                ;; no drafts (:no-output) does NOT fabricate a graph — the failure
                ;; surfaces as a parse-stage anomaly (no false green).
                :rlm-discovery
                (let [run-discovery!
                      (requiring-resolve
                        'ai.obney.orc.ontology.core.rlm-discovery/run-discovery!)
                      compile-discovery-source!
                      (requiring-resolve
                        'ai.obney.orc.ontology.core.rlm-discovery/compile-discovery-source!)
                      descriptor (:source-descriptor source)
                      _ (when-not (map? descriptor)
                          (throw (ex-info (str ":rlm-discovery source requires a "
                                               ":source-descriptor map (the structured "
                                               "source to explore); got "
                                               (pr-str descriptor))
                                          {:source source})))
                      disc-opts (:discovery source)
                      disc-out (run-discovery!
                                 ctx
                                 (merge {:ontology-id ontology-id
                                         :sources [descriptor]}
                                        disc-opts))]
                  (case (:status disc-out)
                    :emitted-drafts
                    (let [stub (compile-discovery-source! ctx ontology-id disc-out)]
                      {:ingested? true
                       :ontology-id ontology-id
                       :discovery-provenance (:discovery-provenance stub)
                       :counts (select-keys (:discovery-provenance stub)
                                            [:concepts-emitted
                                             :relationships-emitted
                                             :axioms-emitted])})

                    ;; No drafts produced — surface honestly (NOT a fabricated
                    ;; graph). The build proceeds with zero new events from this
                    ;; source; the provenance records the zero-yield + trace.
                    :no-output
                    {:ingested? true
                     :ontology-id ontology-id
                     :discovery-provenance {:status :no-output
                                            :concepts-emitted 0
                                            :relationships-emitted 0
                                            :axioms-emitted 0
                                            :rlm-trace (:rlm-trace disc-out)}
                     :counts {:concept 0 :relationship 0}}

                    ;; Session failure — root cause surfaces as a parse anomaly.
                    {:cognitect.anomalies/category :cognitect.anomalies/fault
                     :anomaly/message (str "rlm-discovery source failed at session: "
                                           (:error disc-out))
                     :discovery-output disc-out
                     :source source}))

                ;; Unknown source type — explicit failure, NO silent skip
                {:cognitect.anomalies/category :cognitect.anomalies/incorrect
                 :anomaly/message (str "Unknown source type: " (:type source))
                 :source source})]
          (if (:cognitect.anomalies/category report)
            ;; Halt the parse stage — the anomaly carries the root cause
            {:status :failed
             :stage :parse
             :error {:source source :anomaly report}
             :stage-duration-ms (ms-since start)}
            (recur (rest remaining)
                   (conj reports report))))))))

(defn- referential-integrity-report
  "V18 — the always-on referential-integrity backstop for the assembled
   graph. Given the in-scope concepts + relationships, report whether
   EVERY relationship endpoint resolves to a concept that exists. This is
   a STRUCTURAL INVARIANT independent of the optional shape-gated
   validate-stage (whose short-circuit on absent shapes let V17's 119
   dangling edges survive into the artifact while the build reported
   success).

   The `:rlm-discovery` compile path repairs danglers at emission time by
   auto-minting implied concepts; this backstop is the artifact-level
   GUARANTEE — it surfaces any endpoint that still does not resolve (e.g.
   relationships introduced by a non-discovery source path) so the build
   result can never report a clean success while carrying a dangling edge.

   Domain-agnostic: a pure structural set-membership check, no domain
   knowledge. Returns:
     {:every-edge-endpoint-resolves? <bool>
      :dangling-edge-count <int>
      :dangling-edges [<{:source-uri :target-uri :predicate}> ...]}"
  [concepts relationships]
  (let [concept-uris (set (map :uri concepts))
        dangling (->> relationships
                      (filter (fn [r]
                                (or (not (contains? concept-uris (:source-uri r)))
                                    (not (contains? concept-uris (:target-uri r))))))
                      (mapv (fn [r] (select-keys r [:source-uri :target-uri :predicate])))) ]
    {:every-edge-endpoint-resolves? (empty? dangling)
     :dangling-edge-count (count dangling)
     ;; Cap the surfaced sample so an enormous broken graph doesn't bloat
     ;; the result map; the COUNT is the authoritative no-false-green signal.
     :dangling-edges (vec (take 25 dangling))}))

(defn- normalize-stage
  "Verify the substrate-contract events landed and produce a
   summary the pipeline carries forward. NO additional event
   emission in the TTL path — the ingester already emitted them.
   Returns:
     {:status :ok :concepts-count :relationships-count
      :events-emitted-so-far :referential-integrity :stage-duration-ms}."
  [ctx {:keys [ontology-id]}]
  (let [start (System/currentTimeMillis)
        concepts (filter #(= ontology-id (:ontology-id %))
                         (rm/get-concepts ctx {}))
        relationships (filter #(= ontology-id (:ontology-id %))
                              (rm/get-relationships ctx))
        ;; Read all events in scope so the build summary can carry the
        ;; emitted-event count (an observability win — the prompt's
        ;; result shape requires :events-emitted).
        events-in-scope (count (into [] (es/read (:event-store ctx)
                                                 {:tenant-id (:tenant-id ctx)
                                                  :tags #{[:ontology ontology-id]}})))]
    {:status :ok
     :concepts-count (count concepts)
     :relationships-count (count relationships)
     :events-emitted-so-far events-in-scope
     ;; V18 — always-on referential-integrity backstop (independent of shapes).
     :referential-integrity (referential-integrity-report concepts relationships)
     :stage-duration-ms (ms-since start)}))

(defn candidate-pairs
  "Generate candidate concept pairs for the dedup cascade via REAL
   LSH/MinHash blocking (S12's `dedup/lsh-candidate-pairs`): each concept
   is signed with a MinHash signature over its label features and only
   concepts colliding in a signature band become a pair. This prunes the
   O(n^2) all-pairs set down to the genuinely-similar neighborhoods —
   sub-quadratic in concept count — which is what lets the dedup stage
   complete on a thousands-of-concept graph in minutes instead of
   hot-looping for hours.

   Each pair is `[a b]` with `a-uri < b-uri`; concepts whose label is
   missing (or carries no signable features) are excluded. Delegates to
   the pure dedup-cascade blocker so the LSH neighborhood and the T7
   Jaccard gate measure the SAME similarity — no forked notion."
  [concepts]
  (dedup/lsh-candidate-pairs concepts))

(defn- dedup-stage
  "Run S12's cascade over the blocked candidate pairs in scope.

   DTscale-1 — the stage is the scale-critical orchestration:
     1. LSH/MinHash blocking (`candidate-pairs`) prunes O(n^2) → similar
        neighborhoods.
     2. PROJECT-ONCE: the `:ontology/axioms` (T1 disjointness) and
        `:ontology/concept-evidence` (S13) read-models are projected ONCE
        for the whole stage — NOT once per pair as before (the per-pair
        re-projection was the hot-loop's second compounding fault).
     3. PURE PRE-FILTER: every blocked pair runs the projection-independent
        cheap tiers (`dedup/prefilter-verdict`, threading the projected
        disjointness map) as a PURE function. Pairs the cheap tiers decide
        (disjoint / number / negation / entropy / type / LSH-jaccard) get
        their verdict with NO command, NO event — the vast majority.
     4. Only SURVIVORS (real merge / ambiguity-band candidates) dispatch the
        full `run-dedup-cascade` command, threaded with the SHARED projected
        state so the command does NOT re-project. S13 evidence aggregation
        still runs automatically inside that command for survivors.

   Verdict-invariance: a survivor's full-cascade verdict is unchanged (its
   T1–T5/T7 gates are no-ops — the pre-filter already confirmed none fire —
   so it reaches the SAME T6/T8/T9 verdict). Pre-filtered pairs are TRUE
   non-candidates (KEEP/SKIP guaranteed); their bookkeeping events are
   intentionally not emitted (the cost the slice removes).

   Budget exhaustion in S12 surfaces as a `:requires-review` verdict; the
   skeleton collects them but does NOT halt.

   Returns:
     {:status :ok :pairs-evaluated :candidate-pairs :prefiltered :survivors
      :merges :distinct :skipped :requires-review :verdicts [...]
      :stage-duration-ms}."
  [ctx {:keys [ontology-id alignment-ontology-id llm-budget]
        :or {llm-budget 0}}]
  (let [start (System/currentTimeMillis)]
    (try
      (let [;; The :broader field on the projection is a set; the cascade
            ;; command schema requires `[:vector :string]`. Coerce explicitly.
            concepts (->> (rm/get-concepts ctx {})
                          (filter #(= ontology-id (:ontology-id %)))
                          (mapv (fn [c]
                                  (cond-> (select-keys c [:uri :label :description :type])
                                    (seq (:broader c))
                                    (assoc :broader (vec (:broader c)))))))
            ;; (1) LSH/MinHash blocking — sub-quadratic candidate generation.
            pairs (candidate-pairs concepts)
            ;; (2) PROJECT ONCE for the whole stage (NOT per pair) — BOTH the
            ;;     S07 disjointness axioms (T1 guard) AND the S13 concept-
            ;;     evidence map. Pre-fix each per-pair command re-projected both
            ;;     read-models (a full event-store scan apiece), so the cost was
            ;;     O(pairs × events) — the hot-loop's second fault. Projecting
            ;;     once and threading the snapshots into the survivors' commands
            ;;     reduces this to O(events) for the whole stage.
            ;;
            ;;     The dedup VERDICT is unaffected (it comes from the cascade
            ;;     tiers, not from evidence). The only behavioral nuance is the
            ;;     S13 evidence LEDGER: survivors that share a URI within one
            ;;     stage now aggregate from the same start-of-stage snapshot
            ;;     rather than seeing each other's intra-stage increments. The
            ;;     events still ALL land, so the post-stage projection is
            ;;     correct; only the per-event intermediate aggregate differs.
            ;;     Per-pair evidence re-projection at thousands of concepts was
            ;;     itself a scale wall, so trading exact intra-stage ledger
            ;;     intermediates for the project-once contract is the right call.
            disjointness (or (get-in (rmp/project ctx :ontology/axioms)
                                     [ontology-id :disjointness])
                             {})
            existing-evidence (rmp/project ctx :ontology/concept-evidence)
            ;; (3) PURE pre-filter — partition blocked pairs into cheaply
            ;;     decided (no command) vs survivors (full cascade).
            prefiltered (atom [])
            survivors (atom [])
            _ (doseq [[a b] pairs]
                (if-let [v (dedup/prefilter-verdict
                            {:a a :b b :disjointness-map disjointness})]
                  (swap! prefiltered conj v)
                  (swap! survivors conj [a b])))
            ;; (4) Only survivors hit the full cascade command — threaded with
            ;;     the SHARED projected state so the command does NOT re-project.
            survivor-verdicts
            (mapv (fn [[a b]]
                    (let [result (cp/process-command
                                  (assoc ctx :command
                                         {:command/name :ontology/run-dedup-cascade
                                          :command/id (random-uuid)
                                          :command/timestamp (time/now)
                                          :ontology-id ontology-id
                                          :alignment-ontology-id alignment-ontology-id
                                          :a a :b b
                                          :llm-budget llm-budget
                                          :disjointness disjointness
                                          :existing-evidence existing-evidence}))]
                      ;; Disciplines #5 — no silent fallback. Surface anomalies.
                      (when (:cognitect.anomalies/category result)
                        (throw (ex-info "dedup-stage: cascade command returned anomaly"
                                        {:anomaly result :a a :b b})))
                      (get-in result [:command-result/data :verdict])))
                  @survivors)
            ;; The verdict vector unifies the pre-filtered (cheap) verdicts and
            ;; the survivor (full-cascade) verdicts — the stage's reported
            ;; counts cover BOTH so no decision is silently lost.
            verdicts (into @prefiltered survivor-verdicts)
            by-verdict (group-by :verdict verdicts)]
        {:status :ok
         :pairs-evaluated (count verdicts)
         :candidate-pairs (count pairs)
         :prefiltered (count @prefiltered)
         :survivors (count @survivors)
         :merges (count (get by-verdict :merge []))
         :distinct (count (get by-verdict :distinct []))
         :skipped (count (get by-verdict :skip []))
         :requires-review (count (get by-verdict :requires-review []))
         :verdicts verdicts
         :stage-duration-ms (ms-since start)})
      (catch Exception e
        {:status :failed
         :stage :dedup
         :error {:message (.getMessage e)
                 :data (ex-data e)}
         :stage-duration-ms (ms-since start)}))))

(defn- resolve-shape
  "Resolve a shape-symbol-or-shape into the shape data the lint
   register-shape command takes.

   - When passed a symbol, resolve + deref it (the shape is a `def`).
   - When passed a map, use it directly.
   This lets callers pass `'ai.obney.orc...missing-disjointness-
   shape-symbol` OR the literal shape map."
  [shape-or-sym]
  (cond
    (symbol? shape-or-sym)
    (let [r (requiring-resolve shape-or-sym)]
      (when-not r
        (throw (ex-info "Cannot resolve shape symbol"
                        {:symbol shape-or-sym})))
      @r)

    (map? shape-or-sym)
    shape-or-sym

    :else
    (throw (ex-info "shape must be a symbol or map"
                    {:value shape-or-sym}))))

(defn- validate-stage
  "Register configured shapes + run the S10/S11 lint registry.
   Reads the validation report via the public query, classifies
   findings by severity. Does NOT halt — the pipeline driver
   decides the halt based on the configurable threshold.

   When NO shape symbols are supplied, the stage skips registration
   AND validation, returning `{:skipped? true}`. This makes the
   skeleton legal to invoke without shapes (the consumer simply
   gets no validation pass — no false-negative empty report).

   Returns:
     {:status :ok :violations [...] :warnings [...] :infos [...]
      :skips [...] :stage-duration-ms :skipped?}."
  [ctx {:keys [ontology-id shapes]}]
  (let [start (System/currentTimeMillis)]
    (if (empty? shapes)
      {:status :ok
       :skipped? true
       :violations [] :warnings [] :infos [] :skips []
       :stage-duration-ms (ms-since start)}
      (do
        ;; Register each shape (each command is independent — duplicates
        ;; are handled by the register-shape command's own idempotency).
        (doseq [shape-spec shapes]
          (let [shape (resolve-shape shape-spec)]
            (cp/process-command
             (assoc ctx :command
                    {:command/name :ontology/register-shape
                     :command/id (random-uuid)
                     :command/timestamp (time/now)
                     :ontology-id ontology-id
                     :shape shape}))))
        ;; Run validation. Anomaly when no shapes registered (defensive —
        ;; we just registered them, but a future bug could fail registration).
        (let [val-cmd-result
              (cp/process-command
               (assoc ctx :command
                      {:command/name :ontology/run-validation
                       :command/id (random-uuid)
                       :command/timestamp (time/now)
                       :ontology-id ontology-id}))]
          (if-let [anom (:cognitect.anomalies/category val-cmd-result)]
            {:status :failed
             :stage :validate
             :error {:anomaly val-cmd-result}
             :stage-duration-ms (ms-since start)}
            (let [report (:query/result
                          (lint-q/ontology-get-validation-report
                           (assoc ctx :query {:ontology-id ontology-id})))
                  all-violations (or (:violations report) [])
                  skips (or (:skips report) [])]
              {:status :ok
               :violations (filterv #(= :violation (:severity %)) all-violations)
               :warnings   (filterv #(= :warning (:severity %)) all-violations)
               :infos      (filterv #(= :info (:severity %)) all-violations)
               :skips skips
               :run-id (get-in val-cmd-result [:command-result/value :run-id])
               :stage-duration-ms (ms-since start)})))))))

(defn- scoped-concepts
  "The ontology's concepts from the URI-keyed projection, scoped to
   `ontology-id`. Shared by the embed + index defaults."
  [ctx ontology-id]
  (filterv #(= ontology-id (:ontology-id %)) (rm/get-concepts ctx {})))

(defn- auto-embed!
  "V01 default-embed: DETECT the embeddable fields for the ontology's
   concepts, then embed each concept on the DETECTED fields via the
   public `:ontology/embed-concept` command — which emits
   `:ontology/concept-embedded` events so the embeddings project into the
   concept-embeddings read model and become retrievable via hybrid-search.

   Field detection uses the existing `field-analyzer/detect-embedding-fields`
   capability — V01 wires it in, it does NOT reinvent it. The DEFAULT is the
   HEURISTIC detector (schema/data-shape scan, no LLM): it is deterministic
   and non-blocking, so the skeleton's default build never stalls on an LLM
   call. Callers that want the LLM-driven analyzer opt in with
   `:embed-field-detect :llm` (the analyzer needs the configured LLM — e.g.
   an OpenRouter key — and is exercised by the live-verify path).

   HONEST EMPTY (Disciplines #4): a concept whose detected-field text is
   blank carries nothing to embed; we skip it deterministically (using the
   SAME text builder the command uses) so no event is emitted and
   `:embedded-count` is 0 — no fabricated vectors, no false error.

   Returns `{:embedded-count int :fields-used [...] :detected-fields [...]}`."
  [ctx ontology-id concepts detect-mode]
  (let [detection (if (= detect-mode :llm)
                    (field-analyzer/detect-embedding-fields ctx concepts)
                    (field-analyzer/detect-embedding-fields concepts))
        ;; The embed-concept command's text builder + schema understand only
        ;; these four concept fields. Intersect the detector's choices with
        ;; that enum so an exotic detected field (e.g. a free-form semantic
        ;; string column) doesn't blow the command's schema — and fall back
        ;; to the canonical pair when the intersection is empty so a concept
        ;; with only a label/description is still embedded.
        embeddable-enum #{:label :description :indicators :triggers}
        detected (set (:embedding-fields detection))
        fields-set (let [hit (set/intersection detected embeddable-enum)]
                     (if (seq hit) hit #{:label :description}))
        fields (vec fields-set)
        ;; HONEST EMPTY decision is made HERE, deterministically: a concept
        ;; whose detected-field text is blank carries nothing to embed, so we
        ;; do NOT call the command for it (the command would fault on a nil
        ;; embedding, conflating "no content" with "model error"). This uses
        ;; the SAME text builder the command uses, so the skip decision is
        ;; faithful — no fabricated vectors, no false error.
        embeddable (filterv #(seq (embedding/concept->embedding-text % fields-set))
                            concepts)
        embedded
        (reduce
         (fn [acc concept]
           (let [result (cp/process-command
                         (assoc ctx :command
                                {:command/name :ontology/embed-concept
                                 :command/id (random-uuid)
                                 :command/timestamp (time/now)
                                 :uri (:uri concept)
                                 :fields fields-set}))]
             ;; Disciplines #5 — no silent fallback. Any anomaly here is a
             ;; GENUINE fault (model load failure, concept vanished mid-build)
             ;; — raise with the root cause attached rather than mask it.
             (when (:cognitect.anomalies/category result)
               (throw (ex-info "auto-embed!: embed-concept command returned anomaly"
                               {:anomaly result :uri (:uri concept)})))
             (cond-> acc
               (pos? (or (get-in result [:command-result/data :dimensions]) 0))
               inc)))
         0
         embeddable)]
    {:embedded-count embedded
     :fields-used fields
     :detected-fields fields
     :detection-method (:method detection)}))

(defn- embed-stage
  "Embed the ontology's concepts.

   Caller override: when `:embed-fn` is supplied
   (`(fn [ctx concepts] ...)`), it runs INSTEAD of the default — existing
   callers and tests are unaffected.

   DEFAULT (V01): detect-then-embed. The skeleton detects which concept
   fields carry semantic content and embeds them automatically (closing
   Pillar 2), so a graph built through the skeleton is semantically
   searchable WITHOUT the caller wiring embeddings by hand.
   `:embed-field-detect` selects the detector: `:heuristic` (default,
   deterministic) or `:llm` (opt-in, needs the configured LLM)."
  [ctx {:keys [ontology-id embed-fn embed-field-detect]
        :or {embed-field-detect :heuristic}}]
  (let [start (System/currentTimeMillis)
        concepts (scoped-concepts ctx ontology-id)]
    (if (some? embed-fn)
      (let [result (embed-fn ctx concepts)]
        {:status :ok
         :embed-result result
         :stage-duration-ms (ms-since start)})
      (let [result (auto-embed! ctx ontology-id concepts embed-field-detect)]
        {:status :ok
         :embed-result result
         :embedded-count (:embedded-count result)
         :stage-duration-ms (ms-since start)}))))

(defn- colbert-corpus-too-small?
  "True iff the throwable is ColBERT/FAISS's specific 'too few training
   points to train k-means centroids' error — the well-understood
   minimum-corpus boundary of the PLAID indexer. Matched on the FAISS
   message text (which is the only signal the bridge surfaces). NOT a
   catch-all — every other failure is left for the caller to re-throw."
  [^Throwable t]
  (let [msg (str (.getMessage t)
                 " " (some-> t .getCause .getMessage))]
    (boolean
     (or (re-find #"Number of training points" msg)
         (re-find #"nx >= static_cast" msg)))))

(defn- register-colbert-index!
  "Resolve the bare index-id UUID from `index-concepts!`'s result and
   register it via the public `record-colbert-index` command.

   `index-concepts!` returns its `:index-id` as the FULL create-index!
   result map (whose own `:index-id` key holds the actual UUID). The
   `record-colbert-index` command needs the bare UUID, so we resolve it
   explicitly — accepting either shape (raw UUID, or the nested result
   map) so this stays correct regardless of which form the indexer
   returns. Returns the auto-index! result map."
  [ctx ontology-id result]
  (let [raw-id (:index-id result)
        index-id (if (map? raw-id) (:index-id raw-id) raw-id)]
    (if (and (pos? (or (:document-count result) 0)) (uuid? index-id))
      (let [emit-r (colbert-indexer/emit-colbert-indexed-event!
                    ctx {:ontology-id ontology-id
                         :index-id index-id
                         :index-name (:index-name result)
                         :document-count (:document-count result)
                         :colbert-fields (:colbert-fields result)})]
        ;; Disciplines #5 — surface a genuine command rejection as the
        ;; root cause rather than silently leaving the index unregistered.
        (when (:cognitect.anomalies/category emit-r)
          (throw (ex-info "auto-index!: record-colbert-index command returned anomaly"
                          {:anomaly emit-r :ontology-id ontology-id :index-id index-id})))
        {:indexed? true
         :index-id index-id
         :index-name (:index-name result)
         :document-count (:document-count result)
         :colbert-fields (:colbert-fields result)})
      ;; Index-concepts dropped every document as blank — honest empty.
      {:indexed? false :reason :no-document-content})))

(defn- auto-index!
  "V01 default-index: ColBERT-index the ontology's concepts on
   auto-detected fields via the existing `colbert-indexer/index-concepts!`
   capability, then register the index for the ontology via the public
   `:ontology/record-colbert-index` command (so `get-colbert-index-for-
   ontology` resolves and the hybrid-search ColBERT signal can use it).

   HONEST EMPTY (Disciplines #4): when the embed default produced zero
   embeddings (no semantic content), there is nothing to index — return
   `:indexed? false` and register NO phantom index. `index-concepts!`
   itself drops blank documents, so an index is only registered when real
   document content exists.

   COLBERT CORPUS MINIMUM (Disciplines #4/#5): ColBERT's PLAID/FAISS
   indexer trains k-means centroids and REQUIRES at least k training
   points (token-passages) — small corpora (a handful of short concepts)
   are genuinely below that floor and FAISS raises 'Number of training
   points ... at least as large as number of clusters'. That is a real
   capability boundary of ColBERT, not a bug to mask: we recognize ONLY
   that specific condition and surface it as a structured, NON-fatal
   `:reason :corpus-below-colbert-minimum`. The embeddings still landed and
   remain retrievable via the embedding signal, so the build is not failed
   by it. EVERY OTHER error propagates verbatim (no blanket swallow) — the
   skeleton never hides an unexpected fault.

   Returns `{:indexed? bool :index-id uuid :document-count int
             :colbert-fields [...]}`."
  [ctx ontology-id concepts embedded-count]
  (if (or (empty? concepts) (zero? (or embedded-count 0)))
    {:indexed? false :reason :no-embeddable-content}
    (let [result (try
                   (colbert-indexer/index-concepts!
                    ctx concepts
                    {:auto-detect-colbert-fields true})
                   (catch Exception e
                     ;; Recognize ONLY ColBERT's training-points floor; any
                     ;; other failure re-throws untouched.
                     (if (colbert-corpus-too-small? e)
                       ::corpus-too-small
                       (throw e))))]
      (if (= result ::corpus-too-small)
        {:indexed? false :reason :corpus-below-colbert-minimum}
        (register-colbert-index! ctx ontology-id result)))))

(defn- index-stage
  "ColBERT-index the ontology's concepts.

   Caller override: when `:reindex-fn` is supplied (`(fn [] ...)`), it runs
   INSTEAD of the default — existing callers and tests are unaffected.

   DEFAULT (V01): detect-then-index. The skeleton builds a ColBERT index
   over auto-detected fields when the embed stage produced embeddings."
  [ctx {:keys [ontology-id reindex-fn]} embed-r]
  (let [start (System/currentTimeMillis)]
    (if (some? reindex-fn)
      (let [result (reindex-fn)]
        {:status :ok :reindex-result result :stage-duration-ms (ms-since start)})
      (let [concepts (scoped-concepts ctx ontology-id)
            result (auto-index! ctx ontology-id concepts
                                (:embedded-count embed-r))]
        {:status :ok
         :index-result result
         :stage-duration-ms (ms-since start)}))))

(def default-exit-criterion
  "Default CQ gate per S15's handoff: pass-rate >= 0.8 AND
   unknown-rate <= 0.3. Overridable per build via :exit-criterion."
  {:pass-rate-min 0.8
   :unknown-rate-max 0.3})

(defn- exit-criterion-stage
  "Run the S15 CQ runner when a spec is present. Gate on
   pass-rate/unknown-rate thresholds. Return either:
     - `{:status :ok :graph-health <map>}` when the gate passes
     - `{:status :ok :spec-absent? true}` when no spec is stored
     - `{:status :failed-cq :graph-health :reason}` when the gate
       fails
     - `{:status :failed :error ...}` when the runner itself throws.

   No spec means no gate runs — this is by design. The skeleton
   is legal to invoke without an ORSD spec (callers using the
   ontology 'as a database' without spec semantics)."
  [ctx {:keys [ontology-id judge-fn exit-criterion]
        :or {exit-criterion default-exit-criterion}}]
  (let [start (System/currentTimeMillis)]
    (try
      (let [spec (ontology/get-ontology-spec ctx ontology-id)]
        (cond
          (nil? spec)
          {:status :ok
           :spec-absent? true
           :reason :no-spec
           :stage-duration-ms (ms-since start)}

          (empty? (:competency-questions spec))
          {:status :ok
           :spec-absent? false
           :reason :no-cqs-in-spec
           :stage-duration-ms (ms-since start)}

          :else
          (let [{:keys [graph-health]} (ontology/evaluate-cqs!
                                        {:ctx ctx
                                         :ontology-id ontology-id
                                         :judge-fn judge-fn})
                pass-rate (or (:pass-rate graph-health) 0.0)
                unk-rate (or (:unknown-rate graph-health) 0.0)
                {:keys [pass-rate-min unknown-rate-max]} exit-criterion
                gate-passed? (and (>= pass-rate pass-rate-min)
                                  (<= unk-rate unknown-rate-max))]
            (if gate-passed?
              {:status :ok
               :graph-health graph-health
               :exit-criterion exit-criterion
               :stage-duration-ms (ms-since start)}
              {:status :failed-cq
               :graph-health graph-health
               :exit-criterion exit-criterion
               :reason {:pass-rate pass-rate
                        :pass-rate-min pass-rate-min
                        :unknown-rate unk-rate
                        :unknown-rate-max unknown-rate-max}
               :stage-duration-ms (ms-since start)}))))
      (catch Exception e
        {:status :failed
         :stage :exit-criterion
         :error {:message (.getMessage e)
                 :data (ex-data e)}
         :stage-duration-ms (ms-since start)}))))

;; =============================================================================
;; The pipeline driver
;; =============================================================================

(defn- failure-result [ontology-id stages-run timings stage outcome]
  {:status (keyword (str "failed-at-" (name stage)))
   :stages-run stages-run
   :stage-timings timings
   :ontology-id ontology-id
   :error outcome})

(defn build!
  "Deterministic skeleton build entry point. Drives the seven stages
   in their canonical ordering and returns a structured result.

   Required `params` keys:
     :ontology-id  — UUID; generated if absent (returned in result).
     :sources      — vector of source descriptors. Each carries
                     `:type` and the per-type payload (`:content` for
                     TTL, `:concepts` for inline, `:relationships`
                     for inline-relationships).

   Optional `params` keys:
     :alignment-ontology-id — UUID the dedup cascade tags equivalences
                              to. Defaults to `:ontology-id`.
     :spec                   — IGNORED (spec is read from projection via
                              S14). Callers wire spec via the
                              `:ontology/record-ontology-spec` command
                              before invoking build!.
     :judge-fn              — required when the spec carries CQs that
                              aren't Layer-1-shaped. See S15's
                              `evaluate-cqs!` for the protocol.
     :exit-criterion        — overrides `default-exit-criterion`.
     :llm-budget            — passed to S12 cascade; default 0 (no LLM).
     :validation            — `{:halt-on :violation|:none}`. Default
                              `:violation` (per the prompt's
                              `:violation` HALTS the pipeline rule).
     :shapes                — vector of shape symbols OR shape maps to
                              register before validation runs. When empty,
                              validation is skipped.
     :embed-fn              — `(fn [ctx concepts] {:embedded-count int ...})`.
     :reindex-fn            — `(fn [])` ColBERT reindex trigger.

   Return shape:
     {:status :complete | :failed-validation | :failed-cq |
              :failed-at-parse | :failed-at-normalize |
              :failed-at-dedup | :failed-at-validate |
              :failed-at-embed | :failed-at-index |
              :failed-at-exit-criterion
      :ontology-id <uuid>
      :stages-run [...]
      :stage-timings {<stage> <ms>}
      ;; On :complete only:
      :concepts-count :relationships-count
      :events-emitted
      :dedup-summary {:pairs-evaluated :merges :distinct
                      :requires-review}
      :dedup-review-required [<verdict ...>]   ;; non-empty when
                                                ;; budget-exhausted
      :validation-warnings [...]
      :graph-health {<S15 health map>}
      :spec-absent? <bool>
      :artifacts {:shacl-ttl <str or nil>}
      ;; On any :failed-at-X / :failed-validation / :failed-cq:
      :error <stage-specific error map>
      :violations <when :failed-validation>}

   Failure-shape discipline: an unhandled exception inside a stage is
   captured as `{:status :failed :stage X :error ...}` and the
   pipeline driver translates to `:status :failed-at-X` carrying the
   inner error. The driver does NOT try/catch around stages — only
   the predictable failure paths inside each stage do."
  [ctx params]
  (let [ontology-id (or (:ontology-id params) (random-uuid))
        params (-> params
                   (assoc :ontology-id ontology-id)
                   (update :alignment-ontology-id #(or % ontology-id)))
        halt-on (get-in params [:validation :halt-on] :violation)
        stages-run (atom [])
        timings (atom {})
        record! (fn [stage outcome]
                  (swap! stages-run conj stage)
                  (swap! timings assoc stage (:stage-duration-ms outcome)))

        ;; --- parse ---
        parse-r (parse-stage ctx params)
        _ (record! :parse parse-r)]
    (if (= :failed (:status parse-r))
      (failure-result ontology-id @stages-run @timings :parse parse-r)

      (let [norm-r (normalize-stage ctx params)
            _ (record! :normalize norm-r)]
        (if (= :failed (:status norm-r))
          (failure-result ontology-id @stages-run @timings :normalize norm-r)

          (let [dedup-r (dedup-stage ctx params)
                _ (record! :dedup dedup-r)]
            (if (= :failed (:status dedup-r))
              (failure-result ontology-id @stages-run @timings :dedup dedup-r)

              (let [val-r (validate-stage ctx params)
                    _ (record! :validate val-r)]
                (cond
                  (= :failed (:status val-r))
                  (failure-result ontology-id @stages-run @timings :validate val-r)

                  (and (= halt-on :violation) (seq (:violations val-r)))
                  {:status :failed-validation
                   :stages-run @stages-run
                   :stage-timings @timings
                   :ontology-id ontology-id
                   :violations (:violations val-r)
                   :validation-warnings (:warnings val-r)}

                  :else
                  (let [embed-r (embed-stage ctx params)
                        _ (record! :embed embed-r)]
                    (if (= :failed (:status embed-r))
                      (failure-result ontology-id @stages-run @timings :embed embed-r)

                      (let [idx-r (index-stage ctx params embed-r)
                            _ (record! :index idx-r)]
                        (if (= :failed (:status idx-r))
                          (failure-result ontology-id @stages-run @timings :index idx-r)

                          (let [exit-r (exit-criterion-stage ctx params)
                                _ (record! :exit-criterion exit-r)]
                            (cond
                              (= :failed (:status exit-r))
                              (failure-result ontology-id @stages-run @timings
                                              :exit-criterion exit-r)

                              (= :failed-cq (:status exit-r))
                              {:status :failed-cq
                               :stages-run @stages-run
                               :stage-timings @timings
                               :ontology-id ontology-id
                               :graph-health (:graph-health exit-r)
                               :exit-criterion (:exit-criterion exit-r)
                               :reason (:reason exit-r)
                               :concepts-count (:concepts-count norm-r)
                               :relationships-count (:relationships-count norm-r)
                               :referential-integrity (:referential-integrity norm-r)
                               :dedup-summary {:pairs-evaluated (:pairs-evaluated dedup-r)
                                               :merges (:merges dedup-r)
                                               :distinct (:distinct dedup-r)
                                               :requires-review (:requires-review dedup-r)}
                               :validation-warnings (:warnings val-r)}

                              :else
                              (cond-> {:status :complete
                                       :stages-run @stages-run
                                       :stage-timings @timings
                                       :ontology-id ontology-id
                                       :concepts-count (:concepts-count norm-r)
                                       :relationships-count (:relationships-count norm-r)
                                       :events-emitted (:events-emitted-so-far norm-r)
                                       ;; V18 — referential-integrity is reported on
                                       ;; EVERY completed build (always-on structural
                                       ;; invariant), so an artifact can never be read
                                       ;; as a clean success while carrying dangling
                                       ;; edges — the count + flag are right here.
                                       :referential-integrity (:referential-integrity norm-r)
                                       :validation-warnings (:warnings val-r)
                                       :dedup-summary {:pairs-evaluated (:pairs-evaluated dedup-r)
                                                       :merges (:merges dedup-r)
                                                       :distinct (:distinct dedup-r)
                                                       :requires-review (:requires-review dedup-r)}
                                       :graph-health (:graph-health exit-r)
                                       :spec-absent? (boolean (:spec-absent? exit-r))
                                       :artifacts {:shacl-ttl (serialization/export-shacl-shapes
                                                               ctx ontology-id)}}
                                (pos? (:requires-review dedup-r))
                                (assoc :dedup-review-required
                                       (filterv #(= :requires-review (:verdict %))
                                                (:verdicts dedup-r)))))))))))))))))))
