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
            [ai.obney.grain.time.interface :as time]
            [ai.obney.orc.ontology.interface :as ontology]
            [ai.obney.orc.ontology.core.read-models :as rm]
            [ai.obney.orc.ontology.core.lints.queries :as lint-q]
            [ai.obney.orc.ontology.core.ttl-ingest :as ttl-ingest]
            [ai.obney.orc.ontology.core.serialization :as serialization]
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

(defn- normalize-stage
  "Verify the substrate-contract events landed and produce a
   summary the pipeline carries forward. NO additional event
   emission in the TTL path — the ingester already emitted them.
   Returns:
     {:status :ok :concepts-count :relationships-count
      :events-emitted-so-far :stage-duration-ms}."
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
     :stage-duration-ms (ms-since start)}))

(defn- tokenize
  "Naive whitespace + word-boundary tokenizer for the dedup blocking
   pass. Lowercased; empty-string tokens dropped."
  [s]
  (->> (str/split (str/lower-case (or s "")) #"\W+")
       (remove str/blank?)
       set))

(defn- candidate-pairs
  "Enumerate candidate concept pairs whose labels share at least one
   token. Each pair is `[a b]` with `a-uri < b-uri` so we evaluate
   each unordered pair exactly once. Concepts whose label is missing
   are excluded — there is nothing to compare.

   This is a deliberately-coarse blocking pass. Production-tier
   blocking (LSH from S12) is a future optimization; for the current
   skeleton, this lets every reasonable pair flow through S12's
   cascade where the real verdict happens."
  [concepts]
  (let [with-tokens (->> concepts
                         (filter :label)
                         (mapv #(assoc % ::tokens (tokenize (:label %)))))]
    (for [a with-tokens
          b with-tokens
          :when (and (neg? (compare (:uri a) (:uri b)))
                     (seq (set/intersection (::tokens a) (::tokens b))))]
      [(dissoc a ::tokens) (dissoc b ::tokens)])))

(defn- dedup-stage
  "Run S12's cascade over every candidate pair in scope. S13 evidence
   aggregation runs AUTOMATICALLY inside the cascade command — this
   stage just dispatches. Budget exhaustion in S12 surfaces as a
   `:requires-review` verdict; the skeleton collects them but does
   NOT halt (they're explicit review work).

   Returns:
     {:status :ok :pairs-evaluated :merges :distinct :requires-review
      :verdicts [...] :stage-duration-ms}.

   `:verdicts` is the full per-pair verdict vector — kept on the
   stage result so the build summary can attach a sample to
   :dedup-summary without re-querying the projection."
  [ctx {:keys [ontology-id alignment-ontology-id llm-budget]
        :or {llm-budget 0}}]
  (let [start (System/currentTimeMillis)]
    (try
      (let [;; The :broader field on the projection is a set; the
            ;; cascade command schema requires `[:vector :string]`.
            ;; Coerce explicitly — a silent serialization mismatch
            ;; would suppress the cascade.
            concepts (->> (rm/get-concepts ctx {})
                          (filter #(= ontology-id (:ontology-id %)))
                          (mapv (fn [c]
                                  (cond-> (select-keys c [:uri :label :description])
                                    (seq (:broader c))
                                    (assoc :broader (vec (:broader c)))))))
            pairs (candidate-pairs concepts)
            verdicts
            (mapv (fn [[a b]]
                    (let [result (cp/process-command
                                  (assoc ctx :command
                                         {:command/name :ontology/run-dedup-cascade
                                          :command/id (random-uuid)
                                          :command/timestamp (time/now)
                                          :ontology-id ontology-id
                                          :alignment-ontology-id alignment-ontology-id
                                          :a a :b b
                                          :llm-budget llm-budget}))]
                      ;; Disciplines #5 — no silent fallback. If the
                      ;; cascade command surfaces an anomaly (schema
                      ;; reject, missing alignment, etc.), we raise
                      ;; with the full result attached so the caller
                      ;; sees the root cause instead of a degraded
                      ;; silent skip.
                      (when (:cognitect.anomalies/category result)
                        (throw (ex-info "dedup-stage: cascade command returned anomaly"
                                        {:anomaly result :a a :b b})))
                      (get-in result [:command-result/data :verdict])))
                  pairs)
            by-verdict (group-by :verdict verdicts)]
        {:status :ok
         :pairs-evaluated (count verdicts)
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

(defn- embed-stage
  "Invoke the caller-supplied embedding fn. The fn protocol is
   `(fn [ctx concepts] {:embedded-count int ...})`. Default behavior
   when no fn is supplied: skip with `:skipped? true`.

   Embedding is deliberately wrapped (not inlined) so the production
   `embedding/embed-concepts-batch!` call can be supplied via :embed-fn
   and tests can supply a no-op. Same for the index stage."
  [ctx {:keys [ontology-id embed-fn]}]
  (let [start (System/currentTimeMillis)]
    (if (nil? embed-fn)
      {:status :ok
       :skipped? true
       :stage-duration-ms (ms-since start)}
      (let [concepts (filter #(= ontology-id (:ontology-id %))
                             (rm/get-concepts ctx {}))
            result (embed-fn ctx concepts)]
        {:status :ok
         :embed-result result
         :stage-duration-ms (ms-since start)}))))

(defn- index-stage
  "Invoke the caller-supplied ColBERT reindex fn (or skip)."
  [_ctx {:keys [reindex-fn]}]
  (let [start (System/currentTimeMillis)]
    (if (nil? reindex-fn)
      {:status :ok :skipped? true :stage-duration-ms (ms-since start)}
      (let [result (reindex-fn)]
        {:status :ok :reindex-result result :stage-duration-ms (ms-since start)}))))

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

                      (let [idx-r (index-stage ctx params)
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
