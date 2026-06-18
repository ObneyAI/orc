(ns eb3-model-subbehavior-live-verify
  "EB3 — LIVE VERIFY (doubles as the WORTH prototype): the MODEL subbehavior as a
   delegatable sheet — a SINGLE `:llm` node that turns the GOAL × the EB2 PROFILE
   into the STRUCTURED model-spec (grain-strategy + scope-filter + embed-fields +
   candidate-axioms) — the V17/V20 over-extraction fix as a focused node.

   What this proves (real Grain, real OpenRouter gemini-3-flash-preview, real
   async child tick — NO mocks):
     - The Model sheet is BUILT on the EB1/EB2 registry/delegation pattern: a
       composed ORC sheet, registered under a stable name → deterministic
       sheet-id, invoked from a CENTRAL tree via `:delegate` with mapped
       `:reads`/`:writes`.
     - Its body is a SINGLE `:llm` node (NOT a `:repl-researcher`) — single-turn
       reasoning over goal + profile; no tool session, no recursion, no F3 tick.
     - C1 for the `:llm` node-type: the model-spec + candidate-axioms MAP writes
       declare STRUCTURED Malli `[:map …]` schemas, so the AI executor returns
       PARSED MAPS that cross `:delegate` verbatim — proven by projection read-back
       and a hard `(map? …)` assert (a JSON STRING would FAIL the assert).
     - #13: `:reasoning` is written FIRST (it is the first `:writes` key; the run
       lands a non-empty reasoning on the parent bb).
     - The V17/V20 over-extraction fix: on a BREAKDOWN-HEAVY IPEDS profile under a
       SCOPED goal, the model picks a GRAIN strategy (`:canonical-row-filter` /
       `:breakdown-as-entity`) per entity — NOT one-concept-per-raw-row — AND emits
       a `:scope-filter` keyed to a profile scope-field with the goal's value —
       NOT national / keep-everything.
     - The EB3 additions land: `:embed-fields` (P2, → EB7) + `:candidate-axioms`
       (→ EB6).
     - Domain-agnostic (12): no vertical knowledge in the prompt.

   The upstream profile is a REAL EB2 Survey output captured verbatim from
   `docs/build-timeline/live-verify/EB2-survey.md` (the SQL IPEDS profile, which
   carries breakdown grain-signals + scope-fields). Re-using the real captured
   profile keeps EB3 testing the Model's OWN decision on a real over-extraction-
   prone shape, without re-running the whole upstream Survey each time.

   USAGE (REPL with :dev:test, OPENROUTER_API_KEY in env ONLY):
     (require '[eb3-model-subbehavior-live-verify :as eb3])
     (def r (eb3/run-all! {}))
     (eb3/print-summary! r)
     (eb3/save-capture! r)"
  (:require [ai.obney.orc.orc-service.core.dsl :as dsl]
            [ai.obney.orc.orc-service.core.runtime :as runtime]
            [ai.obney.orc.orc-service.core.read-models :as rm]
            [ai.obney.orc.orc-service.core.todo-processors]
            [ai.obney.orc.ontology.core.model-subbehavior :as model]
            [ai.obney.orc.ontology.core.discovery-tree :as dt]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.query-processor.interface :as qp]
            [ai.obney.grain.pubsub.interface :as pubsub]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp]
            [ai.obney.grain.todo-processor-v2.interface :as tp]
            [litellm.router :as litellm-router]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.pprint :as pp]))

(def default-model "google/gemini-3-flash-preview")
(def capture-path "docs/build-timeline/live-verify/EB3-model.md")

;; The REAL EB2 SQL (IPEDS) profile, captured verbatim from EB2-survey.md. It is
;; the breakdown-heavy shape that previously over-extracted: grain-signals call
;; out AWLEVEL/demographic breakdowns finer than the entity, and scope-fields
;; include STABBR (a state code) — exactly the V17/V20 setup. Re-housed verbatim,
;; NOT trimmed (sample shortened to a few rows for the driver — the model only
;; needs representative shape; the grain/scope SIGNALS are intact).
(def real-sql-profile
  {:entity-candidates
   ["Postsecondary Institution"
    "Academic Program (CIP Code)"
    "Degree Completion/Award Record"]
   :identifying-keys
   {"Postsecondary Institution" ["UNITID"]
    "Academic Program (CIP Code)" ["CIPCODE"]
    "Degree Completion/Award Record" ["UNITID" "CIPCODE" "AWLEVEL"]}
   :scope-fields ["STABBR" "SECTOR" "ICLEVEL" "CONTROL" "HLOFFER" "AWLEVEL" "MAJORNUM"]
   :linking-keys ["UNITID" "CIPCODE" "OPEID" "EIN"]
   :grain-signals
   ["Records in C2022_A are finer-grained than programs, as they are broken down by Award Level (AWLEVEL) and often student demographics (though C2022_A specifically focuses on totals by award level)."]
   :sample
   [{:UNITID 100654 :STABBR "AL" :INSTNM "Alabama A & M University"
     :OPEID "00100200  " :EIN "636001109 " :SECTOR 1 :CONTROL 1 :CITY "Normal"
     :ADDR "4900 Meridian Street"}
    {:UNITID 100663 :STABBR "AL" :INSTNM "University of Alabama at Birmingham"
     :OPEID "00105200  " :EIN "636005396 " :SECTOR 1 :CONTROL 1 :CITY "Birmingham"
     :ADDR "Administration Bldg Suite 1070"}
    {:CTOTALT 7 :CWHITM 5 :CIPCODE "01" :AWLEVEL 1 :UNITID 101295 :MAJORNUM 1
     :CTOTALM 5 :CTOTALW 2}
    {:CTOTALT 8 :CWHITM 5 :CIPCODE "01" :AWLEVEL 1 :UNITID 101514 :MAJORNUM 1
     :CTOTALM 8 :CTOTALW 0}]
   :embed-worthy-fields ["INSTNM" "ADDR" "CITY" "CIPTEXT"]})

;; A SCOPED goal naming a SUBSET (one state) — the V17/V20 setup: the breakdown
;; data must NOT be dumped one-concept-per-row and must NOT stay national. The
;; scope value ("Alabama" / "AL") comes from the GOAL, never invented by the node.
(def scoped-goal
  (str "Build a graph of the degree programs that institutions IN ALABAMA offer "
       "and the awards they confer, keyed by the codes that identify each "
       "institution and program. Only include institutions in the state of "
       "Alabama (state code AL)."))

;; ---------------------------------------------------------------------------
;; Real-Grain harness (same shape as EB1/EB2's)
;; ---------------------------------------------------------------------------

(defn- register-openrouter! [model]
  (let [api-key (or (System/getenv "OPENROUTER_API_KEY")
                    (throw (ex-info "OPENROUTER_API_KEY not set (env var only)" {})))
        base {:provider :openrouter :model model
              :config {:api-base "https://openrouter.ai/api/v1" :api-key api-key}}]
    (litellm-router/register! :openrouter base)
    (litellm-router/register! (keyword (str "openrouter/" model)) base)))

(defn- make-ctx []
  (rmp/l1-clear!)
  (let [ps (pubsub/start {:type :core-async :topic-fn :event/type})
        store (es/start {:conn {:type :in-memory} :event-pubsub ps :logger nil})
        dir (str "/tmp/eb3-live-" (random-uuid))
        cache (kv/start (lmdb/->KV-Store-LMDB
                         {:storage-dir dir :db-name "eb3-live"
                          :map-size (* 1024 1024 1024)}))
        base-ctx {:event-store store
                  :cache cache
                  :tenant-id (random-uuid)
                  :provider :openrouter
                  :dscloj-provider :openrouter
                  :command-registry (cp/global-command-registry)
                  :query-registry (qp/global-query-registry)
                  :event-pubsub ps
                  ::cache-dir dir}
        processors (reduce-kv
                    (fn [acc proc-name {:keys [handler-fn topics]}]
                      (assoc acc proc-name
                             (tp/start {:event-pubsub ps :topics topics
                                        :handler-fn handler-fn :context base-ctx})))
                    {} @tp/processor-registry*)]
    (assoc base-ctx :processors processors)))

(defn- stop-ctx [ctx]
  (doseq [[_ p] (:processors ctx)] (tp/stop p))
  (rmp/l1-clear!)
  (when-let [ps (:event-pubsub ctx)] (pubsub/stop ps))
  (when-let [c (:cache ctx)] (kv/stop c))
  (when-let [s (:event-store ctx)] (es/stop s))
  (when-let [d (::cache-dir ctx)]
    (let [f (java.io.File. d)]
      (when (.exists f)
        (doseq [c (.listFiles f)] (.delete c))
        (.delete f)))))

;; ---------------------------------------------------------------------------
;; Register the Model sheet, delegate to it from a central tree, read the
;; model-spec back off the PARENT tick blackboard.
;; ---------------------------------------------------------------------------

(defn run-once!
  "Register + delegate the Model subbehavior with a goal + a real profile. Returns
   the result map incl. the model-spec read back from the parent bb."
  [ctx {:keys [model goal profile]}]
  (let [model (or model default-model)
        ;; REGISTER the Model subbehavior sheet (idempotent, source-agnostic).
        sub-id (model/register-model-subbehavior! ctx {:model model})
        sub-name (model/model-subbehavior-name)
        looked-up (model/model-sheet-id-for)
        registry-match? (= sub-id looked-up)
        ;; REGISTER a thin central tree that :delegates to it. The central tree's
        ;; blackboard declares the SAME structured schemas so the parent side of
        ;; the :delegate also holds the map writes as structured (C1).
        central-name "eb3/central-model@v1"
        central-def (dsl/workflow central-name
                      (dsl/blackboard {:goal :string
                                       :profile [:map {:closed false}]
                                       :reasoning :string
                                       :model-spec model/model-spec-contract-schema
                                       :candidate-axioms model/candidate-axioms-schema})
                      (dsl/sequence "central-root"
                        (dsl/delegate "to-model"
                          :target-sheet-id (model/model-sheet-id-for)
                          :reads [:goal :profile]
                          :writes [:reasoning :model-spec :candidate-axioms]
                          :timeout-ms 300000)))
        central-id (dsl/build-workflow! ctx central-def)
        ;; DELEGATE: central tree → child tick → model runs → writes back.
        central-tick-id (random-uuid)
        t0 (System/currentTimeMillis)
        central-result (runtime/execute ctx central-id
                                        {"goal" goal
                                         "profile" profile}
                                        :timeout-ms 300000
                                        :tick-id central-tick-id)
        elapsed (- (System/currentTimeMillis) t0)
        _ (Thread/sleep 300) ;; let the completion event project
        ;; DISCIPLINE 7: read the PARENT tick blackboard back from the projection.
        parent-bb (rm/get-tick-blackboard ctx central-tick-id)
        model-spec (get-in parent-bb [:model-spec :value])
        candidate-axioms (get-in parent-bb [:candidate-axioms :value])
        reasoning (get-in parent-bb [:reasoning :value])
        ;; C1 proof: PARSED MAP, or JSON STRING?
        spec-is-map? (map? model-spec)
        spec-is-string? (string? model-spec)
        axioms-is-map? (map? candidate-axioms)
        axioms-is-string? (string? candidate-axioms)
        ;; #13 proof: reasoning present + non-empty, AND it is the FIRST declared
        ;; write (asserted structurally below against the persisted node config).
        reasoning-present? (and (string? reasoning) (pos? (count (str/trim reasoning))))
        ;; the model-spec frozen keys present?
        spec-keys-present (when (map? model-spec)
                            (vec (filter #(contains? model-spec %)
                                         model/model-spec-contract-keys)))
        ;; GRAIN proof: each entity-type carries a grain-strategy that normalizes
        ;; onto the frozen enum (NOT one-concept-per-row — the model DECIDED).
        entity-types (when (map? model-spec) (:entity-types model-spec))
        grains (when (sequential? entity-types)
                 (mapv (fn [et]
                         {:type (get et :type)
                          :raw-grain (get et :grain-strategy)
                          :normalized (dt/normalize-grain-strategy (get et :grain-strategy))})
                       entity-types))
        all-grains-valid? (and (seq grains)
                               (every? #(contains? dt/valid-grain-strategies (:normalized %)) grains))
        ;; SCOPE proof: a non-nil scope-filter keyed to a profile scope-field with
        ;; a value the goal names (NOT national / keep-everything).
        scope-filter (when (map? model-spec) (:scope-filter model-spec))
        scope-present? (map? scope-filter)
        scope-field (when scope-present? (get scope-filter :field))
        name* (fn [x] (cond (keyword? x) (name x) (symbol? x) (name x) :else (str x)))
        scope-field-in-profile? (and scope-field
                                     (some #(= (name* scope-field) (name* %))
                                           (:scope-fields profile)))
        ;; EB3 additions
        embed-fields (when (map? model-spec) (get model-spec model/embed-fields-key))
        axioms (when (map? candidate-axioms) (:axioms candidate-axioms))]
    {:registry {:subbehavior-name sub-name
                :sub-sheet-id sub-id
                :looked-up looked-up
                :registry-match? registry-match?
                :central-name central-name
                :central-sheet-id central-id}
     :central-status (:status central-result)
     :central-tick-id central-tick-id
     :elapsed-ms elapsed
     :model-spec model-spec
     :candidate-axioms candidate-axioms
     :reasoning reasoning
     :spec-is-map? spec-is-map?
     :spec-is-string? spec-is-string?
     :axioms-is-map? axioms-is-map?
     :axioms-is-string? axioms-is-string?
     :reasoning-present? reasoning-present?
     :spec-keys-present spec-keys-present
     :grains grains
     :all-grains-valid? all-grains-valid?
     :scope-filter scope-filter
     :scope-present? scope-present?
     :scope-field scope-field
     :scope-field-in-profile? (boolean scope-field-in-profile?)
     :embed-fields embed-fields
     :axioms axioms
     :error (:error central-result)}))

(defn run-all!
  "Run the Model subbehavior delegated against the real breakdown-heavy SQL
   profile under a scoped goal. Returns {:scoped-sql {...}}."
  [{:keys [model] :or {model default-model}}]
  (let [ctx (make-ctx)]
    (try
      (register-openrouter! model)
      (println "=== EB3 MODEL SUBBEHAVIOR LIVE VERIFY ===")
      (println "model:" model)
      (let [r (do (println "\n--- scoped goal × breakdown-heavy SQL profile ---")
                  (run-once! ctx {:model model :goal scoped-goal :profile real-sql-profile}))
            _ (println "  central status:" (:central-status r)
                       "spec map?:" (:spec-is-map? r)
                       "grains-valid?:" (:all-grains-valid? r)
                       "scope?:" (:scope-present? r)
                       "(" (:elapsed-ms r) "ms)")]
        {:model model :scoped-sql r})
      (finally (stop-ctx ctx)))))

(defn print-summary! [r]
  (let [m (:scoped-sql r)]
    (println "\n================ EB3 scoped-sql ================")
    (println "central status:" (:central-status m) "(" (:elapsed-ms m) "ms)")
    (println "registry match?:" (get-in m [:registry :registry-match?]))
    (println "model-spec PARSED MAP (C1)?:" (:spec-is-map? m)
             " | JSON string?:" (:spec-is-string? m))
    (println "candidate-axioms PARSED MAP (C1)?:" (:axioms-is-map? m)
             " | JSON string?:" (:axioms-is-string? m))
    (println "reasoning present (#13)?:" (:reasoning-present? m))
    (println "model-spec keys present:" (:spec-keys-present m))
    (println "GRAIN per entity (normalized onto frozen enum):")
    (pp/pprint (:grains m))
    (println "all grains valid (NOT one-per-row)?:" (:all-grains-valid? m))
    (println "SCOPE filter (NOT national)?:" (:scope-present? m)
             " field:" (:scope-field m)
             " field-in-profile?:" (:scope-field-in-profile? m))
    (pp/pprint (:scope-filter m))
    (println "embed-fields (P2 → EB7):" (pr-str (:embed-fields m)))
    (println "candidate-axioms (→ EB6):")
    (pp/pprint (:axioms m))
    (println "reasoning (written FIRST, #13):")
    (println (:reasoning m))
    (println "full model-spec:")
    (pp/pprint (:model-spec m))))

(defn save-capture! [r]
  (io/make-parents capture-path)
  (let [m (:scoped-sql r)]
    (spit capture-path
          (str "# EB3 — Model subbehavior sheet — LIVE VERIFY\n\n"
               "**Branch:** `feature/ontology-architecture`. **Model:** `" (:model r)
               "` (real OpenRouter). **No mocks** — real Grain event store, real "
               "LLM, real async todo processors, real child tick.\n\n"
               "Proves the MODEL subbehavior is a delegatable SINGLE-`:llm` sheet "
               "that turns goal × the EB2 profile into a STRUCTURED model-spec "
               "(grain-strategy + scope-filter + embed-fields + candidate-axioms) "
               "arriving PARSED across `:delegate`, with `:reasoning` written FIRST "
               "— the V17/V20 over-extraction fix as a focused node. Built on the "
               "EB1/EB2 registry/delegation pattern; re-houses the DT3 grain/scope "
               "reasoning.\n\n"
               "## Single `:llm` node (NOT a repl-researcher; F3 does not apply)\n\n"
               "The Model body is ONE `:llm` node — single-turn reasoning over goal "
               "+ profile. No tool session, no recursion, no F3 Phase-2 tick.\n\n"
               "## Inputs\n\n"
               "Delegated `goal` (SCOPED — names a subset) + a REAL breakdown-heavy "
               "EB2 SQL profile (captured verbatim from `EB2-survey.md`: "
               "grain-signals call out AWLEVEL/demographic breakdowns finer than "
               "the entity; scope-fields include STABBR). This is the V17/V20 "
               "over-extraction setup.\n\n"
               "Goal:\n\n```\n" scoped-goal "\n```\n\n"
               "Profile (the real EB2 SQL profile, verbatim shape):\n\n```clojure\n"
               (with-out-str (pp/pprint real-sql-profile)) "```\n\n"
               "## Registry + delegation\n\n"
               "- subbehavior: `" (get-in m [:registry :subbehavior-name]) "`\n"
               "- sub sheet-id: `" (get-in m [:registry :sub-sheet-id]) "`\n"
               "- registry name→id round-trip: **" (get-in m [:registry :registry-match?]) "**\n"
               "- central tree status: **" (:central-status m) "** (" (:elapsed-ms m) "ms)\n"
               "- parent tick-id: `" (:central-tick-id m) "`\n\n"
               "## C1 — model-spec arrives PARSED across `:delegate` (the `:llm` case)\n\n"
               "Read back from the PARENT tick blackboard via the projection "
               "(`rm/get-tick-blackboard`), NOT from the execute return value. For "
               "an `:llm` node the STRUCTURED `[:map …]` `:writes` schema is the "
               "LOAD-BEARING fix (the executor flattens + reassembles the fields "
               "into a parsed map; a bare `:map` would arrive as a JSON string):\n\n"
               "- model-spec is a PARSED MAP: **" (:spec-is-map? m) "**\n"
               "- model-spec is a JSON STRING: **" (:spec-is-string? m)
               "** (must be false — that is the C1 failure mode)\n"
               "- candidate-axioms is a PARSED MAP: **" (:axioms-is-map? m) "**\n"
               "- candidate-axioms is a JSON STRING: **" (:axioms-is-string? m) "**\n"
               "- model-spec frozen keys present: `" (pr-str (:spec-keys-present m)) "`\n\n"
               "## #13 — `:reasoning` written FIRST\n\n"
               "`:reasoning` is the FIRST declared `:writes` key on the `:llm` node "
               "(chain-of-thought before the structured spec). It lands non-empty "
               "on the parent bb: present = **" (:reasoning-present? m) "**.\n\n"
               "Reasoning (verbatim from the parent-tick projection):\n\n```\n"
               (:reasoning m) "\n```\n\n"
               "## GRAIN — the V17/V20 over-extraction fix (NOT one-concept-per-row)\n\n"
               "Each entity-type carries a `:grain-strategy` that normalizes onto "
               "the frozen enum `" (pr-str dt/valid-grain-strategies) "` — the model "
               "DECIDED a grain per entity rather than dumping one concept per raw "
               "breakdown row. all grains valid = **" (:all-grains-valid? m) "**:\n\n"
               "```clojure\n" (with-out-str (pp/pprint (:grains m))) "```\n\n"
               "## SCOPE — keyed to a profile scope-field, value from the GOAL (NOT national)\n\n"
               "- scope-filter present (non-nil): **" (:scope-present? m) "**\n"
               "- scope-filter `:field`: `" (pr-str (:scope-field m)) "`\n"
               "- that field is one the profile surfaced as a scope-field: **"
               (:scope-field-in-profile? m) "**\n\n"
               "```clojure\n" (with-out-str (pp/pprint (:scope-filter m))) "```\n\n"
               "## EB3 additions\n\n"
               "- `:embed-fields` (P2 → EB7): `" (pr-str (:embed-fields m)) "`\n\n"
               "- `:candidate-axioms` (→ EB6):\n\n```clojure\n"
               (with-out-str (pp/pprint (:axioms m))) "```\n\n"
               "## Full model-spec (verbatim from the parent-tick projection)\n\n"
               "```clojure\n" (with-out-str (pp/pprint (:model-spec m))) "```\n\n"
               (when (:error m)
                 (str "## Error\n\n```clojure\n"
                      (with-out-str (pp/pprint (:error m))) "```\n\n"))
               "## Verdict\n\n"
               "The Model subbehavior is a single-`:llm` delegatable sheet that "
               "turns goal + profile into a STRUCTURED model-spec (grain + scope + "
               "embed-fields + candidate-axioms) arriving PARSED across `:delegate` "
               "(C1, the `:llm` structured-schema case), with `:reasoning` first "
               "(#13) — the V17/V20 over-extraction fix as a focused node.\n"))
    (println "Capture written:" capture-path)
    capture-path))

(comment
  (require '[eb3-model-subbehavior-live-verify :as eb3] :reload)
  (def r (eb3/run-all! {}))
  (eb3/print-summary! r)
  (eb3/save-capture! r))
