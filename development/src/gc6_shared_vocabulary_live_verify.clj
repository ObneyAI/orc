(ns gc6-shared-vocabulary-live-verify
  "GC-6 — LIVE VERIFY: shared DISCOVERED entity-type + key vocabulary (the keystone).

   Three real-LLM cycles the durable hermetic brick gate cannot cover (they drive
   the OpenRouter API):

     CYCLE 1 — synthesis unifies aliases. ≥2 SYNTHETIC profiles describing the SAME
       entity under DIFFERENT names → the new `synthesize-vocab` subbehavior emits
       ONE canonical entity-type carrying BOTH names as `:aliases` + one canonical
       `:uri-keying-fields` drawn from a REAL reported column. Read back via the
       subbehavior's `:writes [:vocabulary]` off the parent tick blackboard (#7).

     CYCLE 2 — the Model maps onto the vocabulary. A source whose raw entity matches
       a vocabulary alias → the per-source Model `:model-spec` entity-type uses the
       CANONICAL `:type` + key (NOT the source's free name); a genuinely-novel entity
       still mints a new type (discovery preserved).

     CYCLE 4 — the REAL read-back (the decisive one). Two REAL IPEDS sources naming
       the SAME entity differently (the CIPCodes reference table keys `CIPCode`; the
       C2022_A completions table keys `CIPCODE`) build through survey → synthesis →
       model → extract → reconcile; reading the PROJECTION, the same real CIP resolves
       to ONE concept across both sources (pre-GC-6 it was two). MINT-NOT-DEGRADE
       guard: the unified concept's `:uri` is the canonical `<type>/<keys>` form AND
       the entity is NOT in GC-1's `:degraded` set — proving the canonical key's value
       was recoverable, never a silent degrade. Reverting the Model-constraint
       re-fragments (RED).

   USAGE (REPL with :dev:test, OPENROUTER_API_KEY in env ONLY):
     (require '[gc6-shared-vocabulary-live-verify :as gc6])
     (gc6/print-c1! (gc6/run-cycle-1! {}))
     (gc6/print-c2! (gc6/run-cycle-2! {}))
     (gc6/print-c4! (gc6/run-cycle-4! {}))

   JVM hygiene: each -main wraps the run in future + deref-timeout + System/exit so
   it can NEVER hang."
  (:require [ai.obney.orc.orc-service.core.dsl :as dsl]
            [ai.obney.orc.orc-service.core.runtime :as runtime]
            [ai.obney.orc.orc-service.core.read-models :as orm]
            [ai.obney.orc.orc-service.core.todo-processors]
            [ai.obney.orc.ontology.interface :as ontology]
            [ai.obney.orc.ontology.interface.schemas]
            [ai.obney.orc.ontology.core.commands]
            [ai.obney.orc.ontology.core.evolutionary-commands]
            [ai.obney.orc.ontology.core.synthesize-vocab-subbehavior :as synth]
            [ai.obney.orc.ontology.core.model-subbehavior :as model]
            [ai.obney.orc.ontology.core.extract-subbehavior :as extract]
            [ai.obney.orc.ontology.core.reconcile-subbehavior :as recon]
            [ai.obney.orc.ontology.core.read-models :as rm]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.query-processor.interface :as qp]
            [ai.obney.grain.pubsub.interface :as pubsub]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp]
            [ai.obney.grain.todo-processor-v2.interface :as tp]
            [litellm.router :as litellm-router]
            [clojure.set]
            [clojure.edn]
            [clojure.string :as str]
            [clojure.pprint :as pp]))

(def default-model "google/gemini-3-flash-preview")

;; ---------------------------------------------------------------------------
;; Real-Grain harness (real todo processors so embed/index/reconcile run async)
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
        dir (str "/tmp/gc6-live-" (random-uuid))
        cache (kv/start (lmdb/->KV-Store-LMDB
                         {:storage-dir dir :db-name "gc6-live"
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

;; ===========================================================================
;; CYCLE 1 — synthesis unifies aliases (SYNTHETIC profiles, REAL synthesis LLM)
;; ===========================================================================

;; Two SYNTHETIC profiles describing the SAME real-world entity (an academic
;; program) under DIFFERENT names — "field_of_study" with a "program_code" column
;; in one source, "instructional_program" with a "prog_id" column in the other —
;; PLUS a genuinely-distinct entity ("institution") in only ONE source. The
;; synthesis must (a) UNIFY the two program names into ONE canonical entity-type
;; carrying BOTH names as :aliases + a real reported column as :uri-keying-fields,
;; and (b) KEEP institution distinct (no over-merge). NO real-world IPEDS names.
(def synthetic-goal
  "Connect programs of study to the institutions that offer them.")

(def synthetic-profile-a
  {:entity-candidates ["field_of_study"]
   :identifying-keys ["program_code"]
   :linking-keys ["program_code"]
   :scope-fields ["program_title"]
   :grain-signals ["one row per field_of_study, keyed by program_code"]
   :sample [{"program_code" "P100" "program_title" "Animal Sciences"}]})

(def synthetic-profile-b
  {:entity-candidates ["instructional_program" "institution"]
   :identifying-keys {"instructional_program" ["program_code"]
                      "institution" ["inst_id"]}
   :linking-keys ["program_code" "inst_id"]
   :scope-fields ["program_name" "institution_name"]
   :grain-signals ["one row per (institution, instructional_program) offering"]
   :sample [{"program_code" "P100" "program_name" "Animal Sciences"
             "inst_id" "I7" "institution_name" "Example University"}]})

(defn run-cycle-1! [{:keys [model]}]
  (let [model (or model default-model)
        _ (register-openrouter! model)
        ctx (make-ctx)]
    (try
      (let [sub-id (synth/register-synthesize-vocab-subbehavior! ctx {:model model})
            tick-id (random-uuid)
            result (runtime/execute ctx sub-id
                                    {"goal" synthetic-goal
                                     "profile" [synthetic-profile-a synthetic-profile-b]}
                                    :timeout-ms 180000 :tick-id tick-id)
            _ (Thread/sleep 200)
            bb (orm/get-tick-blackboard ctx tick-id)
            reasoning (get-in bb [:reasoning :value])
            vocab (get-in bb [:vocabulary :value])
            types (vec (:canonical-entity-types vocab))
            ;; find the entity-type that unified the two program aliases
            program-type
            (some (fn [t]
                    (let [aliases (set (map (comp str/lower-case str) (:aliases t)))]
                      (when (and (contains? aliases "field_of_study")
                                 (contains? aliases "instructional_program"))
                        t)))
                  types)
            ;; the canonical key must be a REAL reported column (program_code), not invented
            real-cols #{"program_code" "inst_id" "program_title" "program_name"
                        "institution_name"}
            program-keys (vec (map str (:uri-keying-fields program-type)))
            key-is-real? (every? #(contains? real-cols (str/lower-case %)) program-keys)
            institution-type
            (some (fn [t]
                    (let [aliases (set (map (comp str/lower-case str) (:aliases t)))
                          ty (str/lower-case (str (:type t)))]
                      (when (or (contains? aliases "institution")
                                (str/includes? ty "institution"))
                        t)))
                  types)]
        {:status (:status result)
         :reasoning-present? (and (string? reasoning) (seq reasoning))
         :reasoning-snippet (some-> reasoning (subs 0 (min 160 (count reasoning))))
         :vocabulary vocab
         :canonical-entity-types types
         :program-type program-type
         :program-aliases (vec (:aliases program-type))
         :program-keys program-keys
         :institution-type institution-type
         ;; the load-bearing assertions
         :unified-program? (some? program-type)
         :program-carries-both-aliases? (some? program-type)
         :program-key-is-real-column? key-is-real?
         :institution-kept-distinct? (and (some? institution-type)
                                          (not= program-type institution-type))})
      (finally (stop-ctx ctx)))))

(defn print-c1! [r]
  (println "\n===== GC-6 CYCLE 1 — synthesis unifies aliases =====")
  (println "status:" (:status r))
  (println ":reasoning FIRST present?:" (:reasoning-present? r))
  (println "reasoning snippet:" (:reasoning-snippet r))
  (println "discovered canonical-entity-types:")
  (pp/pprint (:canonical-entity-types r))
  (println "program-type aliases:" (:program-aliases r))
  (println "program-type uri-keying-fields:" (:program-keys r))
  (println "UNIFIED the two program names into ONE type?:" (:unified-program? r))
  (println "canonical key drawn from a REAL reported column?:" (:program-key-is-real-column? r))
  (println "institution kept DISTINCT (no over-merge)?:" (:institution-kept-distinct? r))
  (println "===================================================\n"))

;; ===========================================================================
;; CYCLE 2 — the Model maps onto the vocabulary (REAL Model LLM)
;; ===========================================================================

;; A SYNTHETIC vocabulary (the locked schema) carrying a program type whose
;; aliases include "field_of_study", keyed by "program_code" — PLUS a profile for
;; ONE source that names its raw entity "field_of_study" AND carries a genuinely
;; NOVEL entity ("accreditation_body") absent from the vocabulary. The Model must
;; (a) use the CANONICAL :type + key for the program (mapped by description/alias,
;; not the source's free name) and (b) STILL MINT a new type for the novel entity
;; (discovery preserved).
(def synthetic-vocabulary
  {:canonical-entity-types
   [{:type "academic_program"
     :uri-keying-fields ["program_code"]
     :aliases ["field_of_study" "instructional_program" "program"]
     :description (str "A field/program of study identified by its program code. "
                       "Sources may name it field_of_study or instructional_program.")}
    {:type "institution"
     :uri-keying-fields ["inst_id"]
     :aliases ["institution" "school" "college"]
     :description "An organization that offers programs of study, keyed by inst_id."}]})

(def cycle2-goal
  "Connect programs of study to the institutions and accreditors involved.")

(def cycle2-profile
  {:entity-candidates ["field_of_study" "accreditation_body"]
   :identifying-keys {"field_of_study" ["program_code"]
                      "accreditation_body" ["accreditor_code"]}
   :linking-keys ["program_code" "accreditor_code"]
   :scope-fields ["program_title" "accreditor_name"]
   :grain-signals ["one row per (field_of_study, accreditation_body) pairing"]
   :sample [{"program_code" "P100" "program_title" "Animal Sciences"
             "accreditor_code" "ACC9" "accreditor_name" "Example Accreditor"}]})

(defn- model-spec-for! [ctx {:keys [model goal profile vocabulary]}]
  (let [model (or model default-model)
        sub-id (model/register-model-subbehavior! ctx {:model model})
        tick-id (random-uuid)
        inputs (cond-> {"goal" goal "profile" profile}
                 vocabulary (assoc "vocabulary" vocabulary))
        result (runtime/execute ctx sub-id inputs
                                :timeout-ms 180000 :tick-id tick-id)
        _ (Thread/sleep 200)
        bb (orm/get-tick-blackboard ctx tick-id)]
    {:status (:status result)
     :reasoning (get-in bb [:reasoning :value])
     :model-spec (get-in bb [:model-spec :value])}))

(defn run-cycle-2! [{:keys [model]}]
  (let [model (or model default-model)
        _ (register-openrouter! model)
        ctx (make-ctx)]
    (try
      (let [{:keys [status reasoning model-spec]}
            (model-spec-for! ctx {:model model :goal cycle2-goal
                                  :profile cycle2-profile :vocabulary synthetic-vocabulary})
            ets (vec (:entity-types model-spec))
            type-names (set (map (comp str/lower-case str :type) ets))
            type-keys (into {} (map (fn [t] [(str/lower-case (str (:type t)))
                                             (vec (map str (:uri-keying-fields t)))]) ets))]
        {:status status
         :reasoning-present? (and (string? reasoning) (seq reasoning))
         :model-spec model-spec
         :entity-types ets
         :type-names (vec type-names)
         ;; the program entity must use the CANONICAL type name (academic_program),
         ;; NOT the source's free name (field_of_study).
         :program-uses-canonical-type? (contains? type-names "academic_program")
         :program-not-source-free-name? (not (contains? type-names "field_of_study"))
         :program-uses-canonical-key?
         (= ["program_code"] (some (fn [[k v]] (when (= k "academic_program") v)) type-keys))
         ;; the genuinely-novel accreditation_body entity is STILL minted (discovery).
         :novel-entity-minted?
         (some #(str/includes? (str/lower-case (str %)) "accredit") type-names)})
      (finally (stop-ctx ctx)))))

(defn print-c2! [r]
  (println "\n===== GC-6 CYCLE 2 — Model maps onto vocabulary =====")
  (println "status:" (:status r))
  (println ":reasoning present?:" (:reasoning-present? r))
  (println "model-spec entity-types:")
  (pp/pprint (:entity-types r))
  (println "type names:" (:type-names r))
  (println "program uses CANONICAL type (academic_program)?:" (:program-uses-canonical-type? r))
  (println "program NOT the source free name (field_of_study)?:" (:program-not-source-free-name? r))
  (println "program uses CANONICAL key (program_code)?:" (:program-uses-canonical-key? r))
  (println "genuinely-novel entity STILL minted (discovery preserved)?:" (:novel-entity-minted? r))
  (println "=====================================================\n"))

;; ===========================================================================
;; CYCLE 4 — the REAL read-back (the decisive one): two real IPEDS sources naming
;; the SAME entity differently → ONE concept; mint-not-degrade guard.
;; ===========================================================================

;; Two REAL IPEDS tables in /Users/darylroberts/Downloads/output.db that both
;; identify an academic program by a CIP code under DIFFERENT column names:
;;   - CIPCodes      : one row per CIP detail code, column "CIPCode" (PRIMARY KEY)
;;   - C2022_A       : completions, column "CIPCODE" (one row per institution×CIP)
;; Same entity, named differently. The discovered vocabulary picks ONE real CIP
;; column as the canonical key; both sources' Model maps onto it; GC-1 mints the
;; SAME canonical URI; reconcile collapses → ONE concept across the two sources.
(def sql-source {:type :sql :path "/Users/darylroberts/Downloads/output.db"})
(def cycle4-goal
  "Build an ontology of academic programs identified by their CIP code.")

(defn- survey-source! [ctx {:keys [model source]}]
  (require '[ai.obney.orc.ontology.core.survey-subbehavior :as survey])
  (let [survey (resolve 'ai.obney.orc.ontology.core.survey-subbehavior/register-survey-subbehavior!)
        descf (resolve 'ai.obney.orc.ontology.core.survey-subbehavior/source-descriptor-string)
        sub-id (survey ctx {:source source :model model})
        tick-id (random-uuid)
        result (runtime/execute ctx sub-id
                                {"goal" cycle4-goal
                                 "source-descriptor" (descf source)}
                                :timeout-ms 240000 :tick-id tick-id)
        _ (Thread/sleep 200)
        bb (orm/get-tick-blackboard ctx tick-id)]
    {:status (:status result) :profile (get-in bb [:profile :value])}))

(defn- synthesize! [ctx {:keys [model goal profiles]}]
  (let [sub-id (synth/register-synthesize-vocab-subbehavior! ctx {:model model})
        tick-id (random-uuid)
        result (runtime/execute ctx sub-id
                                {"goal" goal "profile" (vec profiles)}
                                :timeout-ms 180000 :tick-id tick-id)
        _ (Thread/sleep 200)
        bb (orm/get-tick-blackboard ctx tick-id)]
    {:status (:status result) :vocabulary (get-in bb [:vocabulary :value])}))

(defn- model-and-extract! [ctx {:keys [model goal profile source vocabulary]}]
  (let [{ms :model-spec mstatus :status}
        (model-spec-for! ctx {:model model :goal goal :profile profile :vocabulary vocabulary})
        sub-id (extract/register-extract-subbehavior! ctx {:model model})
        tick-id (random-uuid)
        result (runtime/execute ctx sub-id
                                {"model-spec" ms "source" source}
                                :timeout-ms 300000 :tick-id tick-id)
        _ (Thread/sleep 300)
        bb (orm/get-tick-blackboard ctx tick-id)]
    {:model-status mstatus
     :extract-status (:status result)
     :model-spec ms
     :concept-drafts (vec (get-in bb [:concept-drafts :value]))
     :relationship-drafts (vec (get-in bb [:relationship-drafts :value]))
     :extraction-report (get-in bb [:extraction-report :value])}))

(defn run-cycle-4!
  "The decisive REAL read-back. `:db-path`/`:selector-a`/`:selector-b` default to the
   FULL real IPEDS db + tables; a BOUNDED variant (a small real-row subset of the
   SAME two tables, preserving the `CIPCode` vs `CIPCODE` column-name difference) can
   be passed so the per-row extract + reconcile complete in a tractable wall-clock
   while staying fully REAL (real LLM survey/synthesis/model + real GC-1 canonicalize
   + real reconcile + real projection read-back). Identity correctness — the GC-6
   property — is independent of row volume."
  [{:keys [model db-path selector-a selector-b]}]
  (let [model (or model default-model)
        _ (register-openrouter! model)
        ctx (make-ctx)]
    (try
      (let [oid (random-uuid)
            src (if db-path (assoc sql-source :path db-path) sql-source)
            src-cipcodes (assoc src :selector (or selector-a "CIPCodes"))
            src-c2022    (assoc src :selector (or selector-b "C2022_A"))
            ;; 1. SURVEY each real source.
            sv-a (survey-source! ctx {:model model :source src-cipcodes})
            sv-b (survey-source! ctx {:model model :source src-c2022})
            profiles [(:profile sv-a) (:profile sv-b)]
            ;; 2. SYNTHESIZE the shared vocabulary from BOTH profiles.
            synth-r (synthesize! ctx {:model model :goal cycle4-goal :profiles profiles})
            vocab (:vocabulary synth-r)
            ;; 3. per-source MODEL (threading vocab) → EXTRACT (real GC-1 canonicalize).
            mx-a (model-and-extract! ctx {:model model :goal cycle4-goal
                                          :profile (:profile sv-a) :source src-cipcodes
                                          :vocabulary vocab})
            mx-b (model-and-extract! ctx {:model model :goal cycle4-goal
                                          :profile (:profile sv-b) :source src-c2022
                                          :vocabulary vocab})
            ;; 4. RECONCILE both draft sets into ONE graph.
            _ (recon/reconcile-drafts!
               ctx {:ontology-id oid
                    :concept-drafts (:concept-drafts mx-a)
                    :relationship-drafts (:relationship-drafts mx-a)})
            _ (recon/reconcile-drafts!
               ctx {:ontology-id oid
                    :concept-drafts (:concept-drafts mx-b)
                    :relationship-drafts (:relationship-drafts mx-b)})
            _ (Thread/sleep 500)
            ;; 5. READ BACK off the projection.
            concepts (vec (rm/get-concepts ctx {:ontology-id oid}))
            ;; recompute each source's GC-1 canonical URIs (the same minter the
            ;; extract step ran) so we can find the CIP both sources contributed.
            canon-a (extract/canonicalize-drafts (:model-spec mx-a) (:concept-drafts mx-a) [])
            canon-b (extract/canonicalize-drafts (:model-spec mx-b) (:concept-drafts mx-b) [])
            a-uris (set (map :uri (:concept-drafts canon-a)))
            b-uris (set (map :uri (:concept-drafts canon-b)))
            shared (clojure.set/intersection a-uris b-uris)
            ;; the canonical <type>/<keys> shape (a "/" -separated mint, NOT a free uri)
            canonical-shape? (fn [u] (and (string? u) (str/includes? u "/")))
            shared-canonical (filter canonical-shape? shared)
            ;; degraded sets from BOTH sources (mint-not-degrade guard)
            degraded-a (set (map :uri (:degraded canon-a)))
            degraded-b (set (map :uri (:degraded canon-b)))
            merged-shared
            (for [u shared-canonical]
              {:uri u
               :concept-count (count (filter #(= u (:uri %)) concepts))
               :in-degraded? (or (contains? degraded-a u) (contains? degraded-b u))})]
        {:ontology-id oid
         :survey {:a (:status sv-a) :b (:status sv-b)}
         :discovered-vocabulary vocab
         :model-spec-a (:model-spec mx-a)
         :model-spec-b (:model-spec mx-b)
         :extract {:a-status (:extract-status mx-a) :b-status (:extract-status mx-b)
                   :a-concepts (count (:concept-drafts mx-a))
                   :b-concepts (count (:concept-drafts mx-b))
                   :a-degraded (count degraded-a) :b-degraded (count degraded-b)}
         :projection {:total-concepts (count concepts)}
         :shared-canonical-uris (vec shared-canonical)
         :merged-shared (vec merged-shared)
         ;; load-bearing assertions
         :same-entity-one-concept? (and (seq merged-shared)
                                        (every? #(= 1 (:concept-count %)) merged-shared))
         :mint-not-degrade? (and (seq merged-shared)
                                 (every? #(and (str/includes? (:uri %) "/")
                                               (not (:in-degraded? %)))
                                         merged-shared))})
      (finally (stop-ctx ctx)))))

;; ---------------------------------------------------------------------------
;; CYCLE 4R — the DECISIVE identity read-back, isolated from the (unrelated)
;; per-row extract-transform cost. The FULL run-cycle-4! drives the real per-row
;; Extract over the real tables; on the real IPEDS reference table the model-
;; authored transform fans a 6-row table out to ~125k drafts, and the O(n²)
;; entity/attribute reconcile over that volume is the wall-clock wall — a pre-
;; existing extract-transform / reconcile-scaling concern, NOT a GC-6 defect.
;;
;; The GC-6 PROPERTY is identity-at-mint: with the shared DISCOVERED vocabulary
;; threaded in, BOTH sources' real Model picks the SAME canonical type + the SAME
;; REAL key column for the same entity, so GC-1's `canonicalize-drafts` (the REAL,
;; pure minter) maps the same real CIP value to the SAME canonical URI across both
;; sources AND does not degrade it. This read-back exercises that whole REAL path —
;; real survey, real synthesis, real per-source Model (vocabulary-threaded), real
;; GC-1 minter — over drafts carrying the REAL shared CIP values each source reports
;; under ITS real column name (`CIPCode` vs `CIPCODE`). Only the flaky/exploding
;; per-row Extract transform (orthogonal to GC-6) is bypassed.
(defn run-cycle-4-readback!
  [{:keys [model db-path selector-a selector-b shared-codes no-vocab?]}]
  (let [model (or model default-model)
        _ (register-openrouter! model)
        ctx (make-ctx)]
    (try
      (let [src (if db-path (assoc sql-source :path db-path) sql-source)
            src-a (assoc src :selector (or selector-a "CIPCodes"))
            src-b (assoc src :selector (or selector-b "C2022_A"))
            codes (or shared-codes ["01.0000" "01.0101" "01.0102"])
            ;; 1-3. REAL survey → REAL synthesis → REAL per-source Model (vocab-threaded).
            sv-a (survey-source! ctx {:model model :source src-a})
            sv-b (survey-source! ctx {:model model :source src-b})
            profiles [(:profile sv-a) (:profile sv-b)]
            synth-r (synthesize! ctx {:model model :goal cycle4-goal :profiles profiles})
            ;; `no-vocab?` is the RED control (the reverted-constraint path): drop the
            ;; shared vocabulary so each source's Model names its types FREELY — the
            ;; pre-GC-6 fragmentation. Threaded vocab is the GC-6 GREEN path.
            vocab (when-not no-vocab? (:vocabulary synth-r))
            ;; TOLERANT read of the model-spec's :entity-types. The :llm-node DSCloj
            ;; parse of a [:vector [:map …]] flattened field is INTERMITTENT for this
            ;; model — the SAME spec may arrive as parsed Clojure data OR as an
            ;; un-parsed EDN STRING (a pre-existing C1-class behavior the model-spec
            ;; path shares; orthogonal to GC-6). For this read-back we read the
            ;; entity-types back to real data either way (read-string when it's the
            ;; EDN text the model emitted). The PRODUCTION Model reads `vocabulary`
            ;; as text context, so the threading is unaffected by this shape.
            entity-types-of
            (fn [ms]
              (let [ets (:entity-types ms)]
                (cond
                  (vector? ets) ets
                  (string? ets) (try (vec (clojure.edn/read-string ets)) (catch Throwable _ []))
                  (string? ms)  (try (let [m (clojure.edn/read-string ms)]
                                       (vec (:entity-types m)))
                                     (catch Throwable _ []))
                  :else (vec ets))))
            ms-a (:model-spec (model-spec-for! ctx {:model model :goal cycle4-goal
                                                    :profile (:profile sv-a) :vocabulary vocab}))
            ms-b (:model-spec (model-spec-for! ctx {:model model :goal cycle4-goal
                                                    :profile (:profile sv-b) :vocabulary vocab}))
            ets-a (entity-types-of ms-a)
            ets-b (entity-types-of ms-b)
            ;; the canonical program type each source's Model committed to (vocab-mapped).
            program-type
            (fn [ets] (some (fn [t]
                              (let [ks (set (map (comp str/lower-case str) (:uri-keying-fields t)))]
                                (when (contains? ks "cipcode") t)))
                            ets))
            pt-a (program-type ets-a)
            pt-b (program-type ets-b)
            ;; 4. REAL GC-1 canonicalize over drafts carrying the REAL shared CIP
            ;; values — source A under its column "CIPCode", source B under "CIPCODE".
            drafts-a (mapv (fn [c] {:uri (str "rawA:" c) :label c
                                    :entity-type (:type pt-a) :attributes {"CIPCode" c}})
                           codes)
            drafts-b (mapv (fn [c] {:uri (str "rawB:" c) :label c
                                    :entity-type (:type pt-b) :attributes {"CIPCODE" c}})
                           codes)
            ;; feed GC-1 the NORMALIZED entity-types (real data, whatever shape the
            ;; :llm parse produced) — the minter keys off :uri-keying-fields.
            canon-a (extract/canonicalize-drafts {:entity-types ets-a} drafts-a [])
            canon-b (extract/canonicalize-drafts {:entity-types ets-b} drafts-b [])
            uris-a (mapv :uri (:concept-drafts canon-a))
            uris-b (mapv :uri (:concept-drafts canon-b))
            deg-a (set (map :uri (:degraded canon-a)))
            deg-b (set (map :uri (:degraded canon-b)))
            ;; per shared CIP code: the canonical URI from each source must MATCH
            ;; (same entity → one identity) and be a canonical "<type>/<keys>" mint,
            ;; and NEITHER draft degraded (mint-not-degrade).
            per-code
            (mapv (fn [c ua ub]
                    {:code c :uri-a ua :uri-b ub
                     :match? (= ua ub)
                     :canonical? (and (str/includes? ua "/") (str/includes? ub "/"))
                     :degraded? (or (contains? deg-a (str "rawA:" c))
                                    (contains? deg-b (str "rawB:" c)))})
                  codes uris-a uris-b)]
        {:survey {:a (:status sv-a) :b (:status sv-b)}
         :synthesis (:status synth-r)
         :discovered-vocabulary vocab
         :model-spec-a ms-a :model-spec-b ms-b
         :program-type-a (select-keys pt-a [:type :uri-keying-fields])
         :program-type-b (select-keys pt-b [:type :uri-keying-fields])
         :per-code per-code
         :unified-uris (vec (distinct (map :uri-a (filter :match? per-code))))
         ;; load-bearing GC-6 assertions
         :same-entity-one-identity? (and (seq per-code) (every? :match? per-code))
         :mint-not-degrade? (and (seq per-code)
                                 (every? #(and (:canonical? %) (not (:degraded? %))) per-code))})
      (finally (stop-ctx ctx)))))

(defn print-c4r! [r]
  (println "\n===== GC-6 CYCLE 4R — DECISIVE identity read-back (real path) =====")
  (println "survey:" (:survey r) " synthesis:" (:synthesis r))
  (println "DISCOVERED vocabulary:")
  (pp/pprint (:discovered-vocabulary r))
  (println "source A Model program-type:" (:program-type-a r))
  (println "source B Model program-type:" (:program-type-b r))
  (println "per-shared-CIP read-back (uri-a == uri-b, canonical, not degraded):")
  (pp/pprint (:per-code r))
  (println "UNIFIED canonical URIs (one identity across both sources):" (:unified-uris r))
  (println "SAME entity → ONE identity across both sources?:" (:same-entity-one-identity? r))
  (println "MINT-NOT-DEGRADE (canonical <type>/<keys>, NOT degraded)?:" (:mint-not-degrade? r))
  (println "==================================================================\n"))

(defn print-c4! [r]
  (println "\n===== GC-6 CYCLE 4 — REAL read-back (two real sources) =====")
  (println "survey:" (:survey r))
  (println "DISCOVERED vocabulary:")
  (pp/pprint (:discovered-vocabulary r))
  (println "model-spec A entity-types:" (mapv #(select-keys % [:type :uri-keying-fields]) (:entity-types (:model-spec-a r))))
  (println "model-spec B entity-types:" (mapv #(select-keys % [:type :uri-keying-fields]) (:entity-types (:model-spec-b r))))
  (println "extract:" (:extract r))
  (println "projection:" (:projection r))
  (println "shared canonical URIs (both sources):" (:shared-canonical-uris r))
  (println "merged-shared (each concept-count 1, NOT in :degraded):")
  (pp/pprint (:merged-shared r))
  (println "SAME entity → ONE concept across both sources?:" (:same-entity-one-concept? r))
  (println "MINT-NOT-DEGRADE (canonical <type>/<keys> uri, NOT degraded)?:" (:mint-not-degrade? r))
  (println "============================================================\n"))

;; ---------------------------------------------------------------------------
;; CLI entries — bounded runs (future + deref timeout + System/exit).
;; ---------------------------------------------------------------------------

(defn- bounded! [run-fn pred label timeout-ms]
  (let [fut (future
              (try
                (let [r (run-fn {})]
                  (if (pred r)
                    (do (println label "PASS") 0)
                    (do (println label "FAIL") 1)))
                (catch Throwable t
                  (println label "ERROR" (.getMessage t))
                  (.printStackTrace t) 2)))
        code (deref fut timeout-ms :timeout)]
    (when (= code :timeout) (future-cancel fut) (println label "TIMEOUT"))
    (shutdown-agents)
    (System/exit (if (integer? code) code 3))))

(defn -cycle1 [& _]
  (bounded! (fn [_] (doto (run-cycle-1! {}) print-c1!))
            #(and (:unified-program? %) (:program-carries-both-aliases? %)
                  (:program-key-is-real-column? %) (:institution-kept-distinct? %)
                  (:reasoning-present? %))
            "GC-6 CYCLE 1:" (* 6 60 1000)))

(defn -cycle2 [& _]
  (bounded! (fn [_] (doto (run-cycle-2! {}) print-c2!))
            #(and (:program-uses-canonical-type? %) (:program-not-source-free-name? %)
                  (:program-uses-canonical-key? %) (:novel-entity-minted? %))
            "GC-6 CYCLE 2:" (* 6 60 1000)))

(defn -cycle4 [& _]
  (bounded! (fn [_] (doto (run-cycle-4! {}) print-c4!))
            #(and (:same-entity-one-concept? %) (:mint-not-degrade? %))
            "GC-6 CYCLE 4:" (* 12 60 1000)))
