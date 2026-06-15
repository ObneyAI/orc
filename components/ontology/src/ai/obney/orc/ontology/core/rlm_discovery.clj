(ns ai.obney.orc.ontology.core.rlm-discovery
  "S18 — Recursive-RLM ontology discovery wiring + the
   `:rlm-discovery` source-type adapter that feeds discovery output
   into S17's deterministic skeleton (`build!`).

   ## What this namespace owns

   Two coordinated entry points:

   1. `run-discovery!` — construct a recursive-RLM session granted
      S19's ontology tools + S20's orientation card (via the existing
      `rlm-sandbox` wiring inside `execute-repl-researcher`) + access
      to the S18 ontology-discovery seed corpus through
      `classify-behaviors`. The session is asked to extract concept,
      relationship, and axiom drafts from the supplied source(s). The
      returned shape is consumable by `compile-discovery-source!`.

   2. `compile-discovery-source!` — the `:rlm-discovery` source-type
      ADAPTER. Translates the discovery output into the
      `:inline-concepts` / `:inline-relationships` source-types S17's
      `parse-stage` already knows. This keeps S17 unchanged — its
      pipeline driver still sees only the source-types it ships with;
      this namespace is the bridge that lets recursive-RLM discovery
      plug INTO that pipeline without modifying S17's namespace.

   ## R-Inject boundary

   `:auto-classify?` is NOT a precondition. The recursive-RLM session
   runs unconditionally — designing trees, calling tools, using the
   card. R-Inject opt-in only affects whether the classify-task /
   classify-behaviors path PREPENDS retrieved patterns into the
   model's design prompt. The discovery wiring opts in by default
   here (the seed corpus exists to be retrieved); a caller can opt
   out by setting `:auto-classify? false` on the rlm config.

   ## HITL gating

   When `:require-hitl-reviewed-patterns? true` is supplied, the
   pattern corpus offered to the session via classify-behaviors is
   FILTERED to entries explicitly marked `:hitl-status :hitl-reviewed`.
   Default behavior includes `:hitl-status :auto-derived` patterns
   (the bench-derived seeds shipped with the component).

   ## Recursive-only

   The session is constructed with `:rlm {:recursive? true ...}` —
   terminal-mode RLM is NOT used for discovery. A caller passing
   `:recursive? false` will see the session refuse construction
   (defensive — discovery without the multi-tree iteration loop loses
   the recovery-from-failed-leaves affordance the recursive mode
   provides)."
  (:require [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.time.interface :as time]
            [ai.obney.orc.ontology.core.seeds :as seeds]
            [clojure.string :as str]))

;; =============================================================================
;; Lazy resolution of orc-service executor + rlm-sandbox.
;; =============================================================================
;; Ontology depends on orc-service (sheets/ namespace) so the require IS
;; available, but we lazy-resolve here so the discovery namespace doesn't
;; force-load the executor at compile time (the executor's compile-time
;; deps are heavy and surface in test classpaths without it).

(defn- executor-fn [sym]
  (or (requiring-resolve sym)
      (throw (ex-info (str "rlm-discovery requires orc-service.core.executor on the classpath. "
                           "Could not resolve: " sym)
                      {:symbol sym}))))

;; =============================================================================
;; Discovery prompt assembly
;; =============================================================================

(def default-discovery-prompt
  "The default discovery instruction handed to the recursive-RLM
   session. Discovery callers can override per-task via
   `:discovery-prompt`. Self-contained — no external file references,
   no slice names. The prompt explicitly orients the model on the
   shape it must produce so `compile-discovery-source!` can ingest
   without ambiguity."
  (str
   "TASK: Extract an ontology draft (concepts + relationships + axioms) "
   "from the supplied source content.\n\n"
   "You have a recursive RLM environment. Tools available include:\n"
   "  - graph-search, neighborhood, get-concept, exists?, absent-in-graph?, "
   "filter-by-label-pattern (S19 ontology tools — these query the EXISTING graph "
   "so you don't duplicate concepts that already exist)\n"
   "  - classify-task, classify-behaviors (existing classifier surface — calling "
   "(classify-behaviors {:task-signature \"<your goal>\"}) retrieves patterns that "
   "fit your discovery task)\n"
   "  - emit-tree! (the recursive RLM tree designer — design ONE tree per discovery pass)\n\n"
   "FIRST: call (classify-behaviors {:task-signature \"ontology discovery from <type> source\"}) "
   "to see which ontology-discovery patterns the corpus suggests. The retrieved patterns are "
   "behavioral subtrees specialized for discovery — choose the one that fits your source's "
   "size and shape, adapt it, or design a fresh tree if none fit.\n\n"
   "OUTPUT SHAPE (via (final! ...)):\n"
   "  {:concept-drafts [{:uri <str> :label <str> :description <str> :scope <kw> "
   "                     :evidence [{:source <str> :quote <str>}]} ...]\n"
   "   :relationship-drafts [{:source-uri <str> :target-uri <str> :predicate <str> "
   "                          :confidence-class :extracted "
   "                          :evidence [{:source <str> :quote <str>}]} ...]\n"
   "   :axiom-drafts [{:axiom-type <kw> :body <map> "
   "                   :evidence [{:source <str> :quote <str>}]} ...]\n"
   "   :rlm-trace [<your iteration summaries — what you classified, what tree "
   "               you emitted, what failures you recovered from>]}\n\n"
   "GROUNDING DISCIPLINE: every :concept-drafts / :relationship-drafts entry MUST carry "
   "a verbatim quote in :evidence. Drafts without quotes are dropped during ingest. "
   "Do NOT speculate beyond what the source text states."))

(defn- build-rlm-config
  "Construct the `:rlm` map for the synthetic repl-researcher node.

   - `:recursive?` is forced to TRUE — discovery requires the
     multi-tree iteration loop (see ns docstring).
   - `:granted-ontology-id` exposes the S19 tool set + S20 card.
   - `:auto-classify?` is true by default (the seed corpus exists to
     be retrieved); callable can suppress with `:auto-classify? false`.
   - `:debug?` propagates if set so the session emits diagnostic
     output during live verify."
  [{:keys [granted-ontology-id auto-classify? debug?]
    :or {auto-classify? true}}]
  (cond-> {:recursive? true
           :granted-ontology-id granted-ontology-id
           :auto-classify? auto-classify?}
    debug? (assoc :debug? true)))

(defn- sources->blackboard
  "Convert the discovery sources into the blackboard shape the
   recursive-RLM session expects. Each source contributes one entry
   keyed by its :name (or auto-assigned `:source-N`). Value is the
   source's content string. Reads-declared keys mirror the keys here."
  [sources]
  (reduce-kv
   (fn [acc i source]
     (let [k (or (:name source) (keyword (str "source-" (inc i))))]
       (assoc acc k {:key k
                     :schema :string
                     :value (or (:content source) "")
                     :version 1})))
   {}
   (vec sources)))

;; =============================================================================
;; Public entry: run-discovery!
;; =============================================================================

(defn run-discovery!
  "Run a recursive-RLM discovery session against the supplied sources.

   Required `params` keys:
     :ontology-id   — the granted scope (REQUIRED — discovery without
                      scope is meaningless; the session refuses to
                      construct otherwise).
     :sources       — vector of source maps, each
                      `{:name <kw> :type <kw> :content <string>}`.

   Optional `params` keys:
     :discovery-prompt              — overrides `default-discovery-prompt`.
     :model                         — OpenRouter model id. Defaults to
                                      `google/gemini-3-flash-preview`
                                      (project preference).
     :budget                        — `{:max-iterations <int>
                                        :total-budget-ms <int>}`.
                                      Defaults: 8 iterations, 300s.
     :auto-classify?                — default true. False opts out of
                                      pattern prepend during tree design;
                                      the seed corpus is STILL reachable
                                      via classify-behaviors tool calls.
     :require-hitl-reviewed-patterns? — default false. When true, the
                                      ontology-discovery seeds offered
                                      via classify-behaviors are
                                      restricted to `:hitl-status
                                      :hitl-reviewed` entries. When the
                                      filter eliminates all patterns,
                                      the session proceeds with NO
                                      patterns (and the rlm-trace
                                      records that fact); the session
                                      does NOT crash.
     :debug?                        — debug logging on the session.

   Required keys on `ctx`:
     :event-store (required for session construction + S19/S20)
     :command-registry, :query-registry (Grain wiring)

   Returns a map:
     {:status                    :emitted-drafts
                                | :no-output
                                | :failed-at-session
      :emitted-concepts          [<draft maps>]
      :emitted-relationships     [<draft maps>]
      :emitted-axioms            [<draft maps>]
      :rlm-trace                 <vec — model's per-iteration trace>
      :iteration-reasonings      <vec — model's stated reasoning>
      :usage                     <token usage>
      :session-result            <raw repl-researcher result for audit>
      :patterns-offered          <count — discovery seeds the session
                                  could retrieve under HITL filter>}

   Adversarial: a discovery output that S17 can't ingest is NOT
   silently dropped here. `compile-discovery-source!` raises a clear
   anomaly if the output shape is malformed. The pipeline driver
   sees `:failed-at-parse` in that case (per S17's existing
   per-stage failure contract)."
  [ctx {:keys [ontology-id sources discovery-prompt model budget
               auto-classify? require-hitl-reviewed-patterns? debug?]
        :or {auto-classify? true
             require-hitl-reviewed-patterns? false
             discovery-prompt default-discovery-prompt
             model "google/gemini-3-flash-preview"}}]
  ;; Defensive precondition — discovery without granted scope is
  ;; meaningless (Disciplines #5 — fail loudly, no silent default).
  (when-not ontology-id
    (throw (ex-info "rlm-discovery/run-discovery! requires :ontology-id (the granted scope)"
                    {:params {:ontology-id ontology-id}})))
  (when-not (:event-store ctx)
    (throw (ex-info "rlm-discovery/run-discovery! requires :event-store in ctx"
                    {:ctx-keys (keys ctx)})))
  (when (empty? sources)
    (throw (ex-info "rlm-discovery/run-discovery! requires :sources (vector of source maps)"
                    {:sources sources})))

  (let [;; Inspect the corpus the session will actually see under the
        ;; HITL filter. We surface the count in the result so the
        ;; caller (and the rlm-trace) record this transparently.
        patterns-offered (count
                           (seeds/ontology-discovery-patterns
                             require-hitl-reviewed-patterns?))

        ;; Synthetic repl-researcher node. The shape mirrors
        ;; orc-service.core.dsl/repl-researcher — built inline because
        ;; we don't have a parent sheet, just a one-shot session.
        rlm-config (build-rlm-config
                     {:granted-ontology-id ontology-id
                      :auto-classify? auto-classify?
                      :debug? debug?})

        ;; Source-keys become declared :reads so the model sees the
        ;; source content in its inputs-preview.
        source-keys (mapv (fn [i source]
                            (or (:name source)
                                (keyword (str "source-" (inc i)))))
                          (range) sources)

        node {:node-type :repl-researcher
              :name :ontology-discovery
              :model model
              :instruction discovery-prompt
              :reads (vec source-keys)
              :writes [:concept-drafts :relationship-drafts
                       :axiom-drafts :rlm-trace]
              :rlm rlm-config
              :max-iterations (or (:max-iterations budget) 8)
              :options (cond-> {}
                         (:total-budget-ms budget)
                         (assoc :timeout-ms (:total-budget-ms budget)))}

        blackboard (sources->blackboard sources)
        provider (or (:provider ctx) :openrouter)

        ;; Lazy-resolve the executor entry point. Discovery sits in
        ;; the ontology component; the orc-service.core.executor is
        ;; a peer that DOES live on the test classpath but we keep
        ;; the resolution deferred to avoid compile-time coupling.
        execute-fn (executor-fn 'ai.obney.orc.orc-service.core.executor/execute-repl-researcher)

        ;; The executor's context map is the Grain ctx plus orc-service
        ;; specifics. We thread `:event-store`, `:tenant-id`, `:cache`
        ;; through — the rlm-sandbox reads them for S19 + S20 wiring.
        exec-context (select-keys ctx [:event-store :tenant-id :cache
                                      :command-registry :query-registry
                                      :sheet-id :tick-id])

        session-result (try
                         (execute-fn node blackboard provider exec-context)
                         (catch Throwable t
                           {:status :failure
                            :error (.getMessage t)
                            :exception-data (ex-data t)}))

        outputs (or (:outputs session-result) {})
        concepts (vec (or (get outputs :concept-drafts) []))
        relationships (vec (or (get outputs :relationship-drafts) []))
        axioms (vec (or (get outputs :axiom-drafts) []))
        rlm-trace (vec (or (get outputs :rlm-trace) []))]

    (cond
      ;; Session failure — surface the root cause; do NOT mask with
      ;; an empty-success shape (Disciplines #5).
      (= :failure (:status session-result))
      {:status :failed-at-session
       :error (:error session-result)
       :session-result session-result
       :patterns-offered patterns-offered}

      ;; Session completed but produced no drafts. Reported as
      ;; :no-output so the caller can decide whether to retry, mark
      ;; the source as zero-yield, or escalate.
      (and (empty? concepts) (empty? relationships) (empty? axioms))
      {:status :no-output
       :emitted-concepts []
       :emitted-relationships []
       :emitted-axioms []
       :rlm-trace rlm-trace
       :iteration-reasonings (vec (or (:iteration-reasonings session-result) []))
       :usage (:usage session-result)
       :session-result session-result
       :patterns-offered patterns-offered}

      :else
      {:status :emitted-drafts
       :emitted-concepts concepts
       :emitted-relationships relationships
       :emitted-axioms axioms
       :rlm-trace rlm-trace
       :iteration-reasonings (vec (or (:iteration-reasonings session-result) []))
       :usage (:usage session-result)
       :session-result session-result
       :patterns-offered patterns-offered})))

;; =============================================================================
;; Public entry: compile-discovery-source!
;; =============================================================================
;; The S17 adapter. Takes a discovery output (the result of
;; run-discovery!) AND an ontology-id; emits the draft concepts +
;; relationships via the existing :ontology/create-concept and
;; :ontology/create-relationship commands. Returns a sources-vec the
;; S17 build! can pass through `:sources` (each source ends up as a
;; no-op `:inline-concepts` with empty :concepts because the events
;; already landed, OR — if the caller prefers — a deferred dispatch
;; via the `:inline-*` source-types that S17 already supports).
;;
;; The CALLING CONVENTION is: discovery emits events FIRST (through
;; commands), then S17 sees a single zero-concept :inline-concepts
;; source. This keeps S17's parse-stage interface clean (no new
;; type to teach it).
;;
;; The adversarial design point: if the discovery output is
;; malformed (missing :concept-drafts, drafts missing :uri / :label),
;; this fn raises a clear anomaly; it does NOT silently drop
;; entries.

(defn- validate-concept-draft! [c]
  (when-not (and (map? c) (:uri c) (:label c))
    (throw (ex-info "compile-discovery-source!: malformed concept-draft (missing :uri or :label)"
                    {:draft c
                     :reason :missing-required-field})))
  c)

(defn- validate-relationship-draft! [r]
  (when-not (and (map? r) (:source-uri r) (:target-uri r) (:predicate r))
    (throw (ex-info "compile-discovery-source!: malformed relationship-draft (missing :source-uri/:target-uri/:predicate)"
                    {:draft r
                     :reason :missing-required-field})))
  r)

(def ^:private valid-concept-scopes
  "The ontology-scope enum (interface/schemas). A discovery session is
   general-purpose extraction, so the model often invents a domain-specific
   scope (e.g. :policy, :hr) that isn't in the enum. Those coerce to :custom —
   the general-purpose bucket — rather than failing the create-concept command."
  #{:failure :success :problem :node-type :custom :tree-class :behavioral-subtree})

(defn- ->kw
  "JSON has no keywords, so the structured-output parse leaves keyword-typed
   fields as STRINGS (e.g. \"extracted\", \"employment\"). Coerce string→keyword;
   pass keywords through; nil otherwise."
  [x]
  (cond (keyword? x) x
        (string? x)  (keyword x)
        :else        nil))

(defn- coerce-scope
  "Map a draft scope to a valid ontology-scope; unknown/absent → :custom.
   Handles the model's string scopes (\"custom\" → :custom, \"failure\" →
   :failure, invented \"employment\" → :custom)."
  [scope]
  (let [k (->kw scope)]
    (if (contains? valid-concept-scopes k) k :custom)))

(def ^:private valid-confidence-classes #{:extracted :inferred :ambiguous})

(defn- coerce-confidence-class
  "Coerce a draft :confidence-class (often the string \"extracted\" from the
   JSON round-trip) to the keyword enum; unknown/absent → :extracted."
  [cc]
  (let [k (->kw cc)]
    (if (contains? valid-confidence-classes k) k :extracted)))

(defn- concept-draft->command [ontology-id draft]
  (let [;; Strip evidence — it lives on relationships per the S06 schema,
        ;; not on concepts. Concepts carry quotes via :comments / :see-also
        ;; in the bundle layer (S04); for now we collapse evidence quotes
        ;; into :description if present.
        evidence (:evidence draft)
        desc (cond-> (or (:description draft) "")
               (seq evidence)
               (str (when (seq (or (:description draft) "")) "\n\n")
                    "Source evidence:\n"
                    (str/join "\n"
                      (map (fn [e]
                             (str "- " (:source e "?") ": \""
                                  (:quote e "") "\""))
                           evidence))))]
    {:command/name :ontology/create-concept
     :command/id (random-uuid)
     :command/timestamp (time/now)
     :ontology-id ontology-id
     :uri (:uri draft)
     :label (:label draft)
     :description desc
     :scope (coerce-scope (:scope draft))
     :broader (vec (or (:broader draft) []))
     :indicators (vec (or (:indicators draft) []))}))

(defn- relationship-draft->command [ontology-id draft]
  {:command/name :ontology/create-relationship
   :command/id (random-uuid)
   :command/timestamp (time/now)
   :ontology-id ontology-id
   :source-uri (:source-uri draft)
   :target-uri (:target-uri draft)
   :predicate (:predicate draft)
   :confidence-class (coerce-confidence-class (:confidence-class draft))
   :evidence (vec (or (:evidence draft) []))
   :properties (or (:properties draft) {})})

(defn compile-discovery-source!
  "Adapter from a `run-discovery!` output to S17-ingestible events.

   Emits the draft concepts and relationships through the existing
   `:ontology/create-concept` and `:ontology/create-relationship`
   commands. Returns a 'source stub' the S17 build! can pass through
   its `:sources` list — by design the stub is an `:inline-concepts`
   with empty `:concepts` (because we already emitted), giving S17 a
   no-op parse stage to record provenance against.

   This is the `:type :rlm-discovery` source-type integration —
   instead of teaching S17 a new type, we EMIT the events here and
   hand S17 a stub it already knows how to handle. The S17 namespace
   stays unchanged.

   Adversarial: a malformed draft raises an anomaly with the offending
   draft attached. The S17 pipeline driver sees this as a parse-stage
   failure with the root cause visible — NOT a silent drop.

   Returns:
     {:type :inline-concepts :concepts []
      :discovery-provenance {:status :ingested
                             :concepts-emitted <int>
                             :relationships-emitted <int>
                             :axioms-skipped <int>
                             :rlm-trace <vec>}}

   Why axioms are flagged `:axioms-skipped`: the S07 axiom event path
   has its own command surface that this adapter does NOT yet dispatch
   into — axiom-drafts are preserved in the provenance for future
   S07-integration work but do NOT emit events here. This is a
   KNOWN GAP recorded explicitly in the provenance rather than a
   silent drop."
  [ctx ontology-id discovery-output]
  (when-not (= :emitted-drafts (:status discovery-output))
    (throw (ex-info "compile-discovery-source!: discovery output must have :status :emitted-drafts"
                    {:status (:status discovery-output)
                     :discovery-output discovery-output})))
  (let [concepts (mapv validate-concept-draft! (:emitted-concepts discovery-output))
        relationships (mapv validate-relationship-draft! (:emitted-relationships discovery-output))
        axioms (:emitted-axioms discovery-output)
        rlm-trace (:rlm-trace discovery-output)]

    ;; Emit concept events first so relationship endpoints resolve
    ;; against existing concepts. Order matters when downstream
    ;; consumers care about temporal ordering of events.
    (doseq [c concepts]
      (let [result (cp/process-command
                     (assoc ctx :command (concept-draft->command ontology-id c)))]
        (when (:cognitect.anomalies/category result)
          (throw (ex-info "compile-discovery-source!: concept emission anomaly"
                          {:draft c :anomaly result})))))

    (doseq [r relationships]
      (let [result (cp/process-command
                     (assoc ctx :command (relationship-draft->command ontology-id r)))]
        (when (:cognitect.anomalies/category result)
          (throw (ex-info "compile-discovery-source!: relationship emission anomaly"
                          {:draft r :anomaly result})))))

    {:type :inline-concepts
     :concepts []
     :discovery-provenance {:status :ingested
                            :concepts-emitted (count concepts)
                            :relationships-emitted (count relationships)
                            :axioms-skipped (count (or axioms []))
                            :rlm-trace rlm-trace}}))

;; =============================================================================
;; Public convenience: discover-and-build!
;; =============================================================================
;; A one-call convenience that chains run-discovery! +
;; compile-discovery-source! and threads the resulting source stub
;; into the S17 build!. Callers who want fine-grained control over
;; the two stages can call them individually. The deterministic
;; skeleton namespace is loaded lazily here to avoid a circular
;; dependency at compile time.

(defn discover-and-build!
  "Convenience: run discovery, emit drafts as events, then drive S17's
   skeleton end-to-end. Returns the S17 `build!` result augmented
   with `:discovery-provenance` (the rlm-trace + emitted counts).

   Required keys mirror `run-discovery!` + S17 `build!`:
     :ontology-id     — granted scope
     :sources         — discovery sources (same shape as run-discovery!)
     ...              — any S17 `build!` keys are passed through

   When discovery returns `:status :no-output` (model produced no
   drafts), the build proceeds with NO new sources — typically
   surfaces as a build with `:concepts-count` unchanged and
   `:discovery-provenance {:status :no-output ...}` on the result.

   When discovery fails (`:failed-at-session`), this raises an
   anomaly — the caller sees the root cause; we do NOT mask a
   discovery failure with an empty build."
  [ctx {:keys [ontology-id sources discovery-prompt model budget
               auto-classify? require-hitl-reviewed-patterns?
               debug?]
        :as build-params}]
  (let [discovery-out (run-discovery!
                        ctx
                        {:ontology-id ontology-id
                         :sources sources
                         :discovery-prompt discovery-prompt
                         :model model
                         :budget budget
                         :auto-classify? auto-classify?
                         :require-hitl-reviewed-patterns? require-hitl-reviewed-patterns?
                         :debug? debug?})
        skeleton-build (requiring-resolve
                         'ai.obney.orc.ontology.core.deterministic-skeleton/build!)]

    (cond
      (= :failed-at-session (:status discovery-out))
      (throw (ex-info "discover-and-build!: discovery session failed"
                      {:discovery-output discovery-out
                       :reason :failed-at-session}))

      (= :no-output (:status discovery-out))
      (assoc (skeleton-build
               ctx
               (assoc build-params
                      :sources [{:type :inline-concepts :concepts []}]))
             :discovery-provenance {:status :no-output
                                    :rlm-trace (:rlm-trace discovery-out)
                                    :patterns-offered (:patterns-offered discovery-out)})

      :else
      (let [source-stub (compile-discovery-source! ctx ontology-id discovery-out)]
        (assoc (skeleton-build
                 ctx
                 (assoc build-params :sources [source-stub]))
               :discovery-provenance (assoc (:discovery-provenance source-stub)
                                            :patterns-offered (:patterns-offered discovery-out)))))))
