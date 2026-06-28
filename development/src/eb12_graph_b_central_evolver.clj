(ns eb12-graph-b-central-evolver
  "EB12 — Graph B rebuild via the CURRENT architecture (the composed central
   evolver), for the A2-vs-B head-to-head.

   This replaces the V17 driver's PRE-EB `run-discovery!`-per-source path with the
   CURRENT `run-central-evolver!` (EB10/EB11 keystone): the central tree COMPOSES
   the EB2-EB9 subbehaviors via `:delegate` (Survey → Model→Extract → Reconcile →
   Axiom → Embed+Index → derive-CQs) and pursues CQ-satisfaction as its objective,
   with the S15 gate run IN-PROCESS by a real LLM judge. Cross-source reconcile
   (EB5) runs per source against the accumulating graph, so program↔CIP↔SOC links
   are the builder's own discovery.

   ENGINE: EB10's invocation (judge-fn + ctx-with-real-todo-processors).
   SOURCES + GOAL + READ-BACK ANALYSIS: V17's (the 5 official BRYC sources, the
   no-hardcoding domain goal, and the schema/predicate-agnostic graph-stats /
   connectivity-proof / earnings-verdict / retrievability probes that feed the
   A2-vs-B comparison).

   THE LOAD-BEARING RULE (auditable): the only per-source text handed to the
   builder is the DOMAIN GOAL — no table/column/offset/LIMIT/join/key/crosswalk.
   Any cross-source link in graph B is the builder's own discovery.

   No mocks. Real Grain event store, real OpenRouter LLM, real local embeddings
   (all-MiniLM-L6-v2, 384-dim), real ColBERT. No false green — a disconnected
   graph / missed bridge / under-coverage is reported AS-IS.

   USAGE (bounded CLI): clj -M:dev:test -m eb12-graph-b-central-evolver
   or REPL: (require '[eb12-graph-b-central-evolver :as b]) (def r (b/run! {}))
            (b/print-summary! r) (b/save-capture! r)"
  (:require [ai.obney.orc.orc-service.core.todo-processors]
            [ai.obney.orc.ontology.interface :as ontology]
            [ai.obney.orc.ontology.interface.schemas]
            [ai.obney.orc.ontology.core.commands]
            [ai.obney.orc.ontology.core.read-models :as rm]
            [ai.obney.orc.ontology.core.cq-runner :as cqr]
            [ai.obney.orc.ontology.core.central-evolver :as ce]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.event-store-v3.interface :as es]
            ;; GC-4 — load the SQLite-v3 store impl so `(es/start {:conn {:type
            ;; :sqlite ...}})` resolves its multimethod (the EXACT precedent in
            ;; build_atomicity_test.clj requires this ns for the same reason).
            [ai.obney.grain.event-store-sqlite-v3.interface]
            [ai.obney.grain.query-processor.interface :as qp]
            [ai.obney.grain.pubsub.interface :as pubsub]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp]
            [ai.obney.grain.todo-processor-v2.interface :as tp]
            [dscloj.core :as dscloj]
            [litellm.router :as litellm-router]
            [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.pprint :as pp]))

;; =============================================================================
;; The 5 official sources (PATH + FORMAT ONLY — no contents fed) — from V17.
;; =============================================================================

(def ipeds-db      "/Users/darylroberts/Downloads/output.db")
(def crosswalk-csv "/Users/darylroberts/Downloads/cip_soc_crosswalk.csv")
(def onet-dir      "/Users/darylroberts/Downloads/db_30_1_excel")
(def wages-csv     "/Users/darylroberts/Desktop/Code/area_51/dspy_notebooks/bryc-workshop/components/recommendations/resources/recommendations/louisiana_occupation_wages.csv")
(def pseo-xlsx     "/Users/darylroberts/Downloads/pseo_la.xlsx")

(def default-model "google/gemini-3-flash-preview")
(def capture-path  "docs/build-timeline/live-verify/EB12-graph-b-central-evolver.md")
(def artifact-path "docs/build-timeline/live-verify/EB12-graph-b-central-evolver-artifact.edn")

(defn sources []
  [{:name :ipeds     :type :sql   :path ipeds-db}
   {:name :crosswalk :type :csv   :path crosswalk-csv}
   {:name :onet      :type :excel :path onet-dir}
   {:name :wages     :type :csv   :path wages-csv}
   {:name :pseo      :type :excel :path pseo-xlsx}])

;; =============================================================================
;; THE DOMAIN GOAL — the ONLY per-source text (verbatim from V17). A goal, NOT a
;; recipe. Names NO table/column/index/offset/LIMIT/key/join.
;; =============================================================================

(def domain-goal
  (str
   "DOMAIN GOAL — build a comprehensive, connected ontology over these Louisiana "
   "education-and-career sources: the educational programs offered, the fields of "
   "study they belong to, the occupations those fields lead to, the institutions "
   "that offer them, and the earnings / wage outcomes associated with them.\n\n"
   "Cover the Louisiana program set COMPREHENSIVELY. Where the source you are "
   "exploring is large, PAGE through it (use the :offset window affordance of the "
   "sampling tools) until you have covered the entities the goal asks for — do not "
   "settle for the first window. The deterministic transform you design runs over "
   "ALL the rows you gather at no extra cost, so retrieve the full relevant set, "
   "not a token sample.\n\n"
   "Where different sources refer to the same real-world entity, MERGE them by "
   "minting the SAME canonical identifier (a stable, shareable id derived from the "
   "code system the source itself uses), so a concept this source contributes and "
   "a concept another source contributes for the same real thing resolve to ONE "
   "node. FIND and USE whatever shared keys or crosswalk information the sources "
   "THEMSELVES provide to connect across sources — explore the source to discover "
   "what those keys are; they are not given to you.\n\n"
   "Carry any numeric OUTCOME a concept has (earnings, wages, employment, growth, "
   "tuition, percentiles) in :attributes as native numbers so they stay queryable.\n\n"
   "This is ONE of several sources that together form the connected graph; mint "
   "your concepts so they will link up with the others by shared canonical id.\n\n"))

;; =============================================================================
;; Provider + the real LLM judge (the in-process S15 gate) — from EB10.
;; =============================================================================

(defn register-openrouter! [model]
  (litellm-router/register! :openrouter
                            {:provider :openrouter
                             :model model
                             :config {:api-base "https://openrouter.ai/api/v1"
                                      :api-key (or (System/getenv "OPENROUTER_API_KEY")
                                                   (throw (ex-info "OPENROUTER_API_KEY not set (env only)" {})))}}))

(defn real-llm-judge [{:keys [question evidence]}]
  (let [prompt (cqr/render-judge-prompt question evidence)
        module {:inputs  [{:name :request :spec :string :description "The CQ + evidence"}]
                :outputs [{:name :verdict :spec :string :description "pass, fail, or unknown"}
                          {:name :reasoning :spec :string :description "Why; on unknown name the gap"}
                          {:name :evidence-uris :spec [:vector :string] :description "URIs used"}
                          {:name :gaps :spec [:vector :string] :description "Missing fact-kinds on unknown"}]
                :instructions prompt}
        result (dscloj/predict :openrouter module
                               {:request "Evaluate per the rubric above."}
                               {:validate? false :with-metadata? false})
        outputs (or (:outputs result) result)
        raw (str/trim (str/lower-case (or (:verdict outputs) "")))
        verdict (cond
                  (#{"pass" "yes" "true"} raw) :pass
                  (#{"fail" "no" "false"} raw) :fail
                  (#{"unknown" "uncertain"} raw) :unknown
                  :else (throw (ex-info "Judge returned unparseable verdict" {:raw raw})))]
    {:verdict verdict
     :reasoning (or (:reasoning outputs) "")
     :evidence-uris (vec (or (:evidence-uris outputs) []))
     :gaps (vec (or (:gaps outputs) []))}))

;; =============================================================================
;; Real-Grain harness WITH the real todo processors (the :delegate child tick is
;; driven by a todo-processor). 4 GB LMDB (default 10 MB MapFull-crashes at scale).
;; =============================================================================

(defn- make-ctx
  "Build the real-Grain harness ctx. GC-4 store knob: `:store` selects the event
   store impl — `:sqlite` (DEFAULT — a PERSISTENT SQLite-v3 file on disk, so the
   comprehensive build's event log does NOT live in heap and can't OOM) or
   `:in-memory` (the original heap log, still fine for tiny smokes that don't
   want a disk file). The SQLite db-file is placed under the same per-run dir as
   the LMDB cache and threaded on the ctx as `::db-file` so `stop-ctx` deletes it.

   The wiring MIRRORS the EXACT real precedent in
   `orc-service/.../build_atomicity_test.clj` `sqlite-ctx` (`:conn {:type :sqlite
   :database-file db-file :maximum-pool-size 4}`)."
  ([] (make-ctx {}))
  ([{:keys [store] :or {store :sqlite}}]
   (rmp/l1-clear!)
   (let [ps (pubsub/start {:type :core-async :topic-fn :event/type})
         dir (str "/tmp/eb12-graph-b-" (random-uuid))
         db-file (when (= store :sqlite) (str dir "-events.db"))
         store-impl (case store
                      :sqlite   (es/start {:conn {:type :sqlite
                                                  :database-file db-file
                                                  ;; >1 so the store is genuinely
                                                  ;; concurrent (the precedent's note).
                                                  :maximum-pool-size 4}
                                           :event-pubsub ps :logger nil})
                      :in-memory (es/start {:conn {:type :in-memory}
                                            :event-pubsub ps :logger nil})
                      (throw (ex-info "make-ctx :store must be :sqlite or :in-memory"
                                      {:store store})))
         cache (kv/start (lmdb/->KV-Store-LMDB
                          {:storage-dir dir :db-name "graph-b"
                           :map-size (* 4 1024 1024 1024)}))
         base-ctx (cond-> {:event-store store-impl :cache cache :tenant-id (random-uuid)
                           :provider :openrouter :dscloj-provider :openrouter
                           :command-registry (cp/global-command-registry)
                           :query-registry (qp/global-query-registry)
                           :event-pubsub ps ::cache-dir dir ::store store}
                    db-file (assoc ::db-file db-file))
         processors (reduce-kv
                     (fn [acc proc-name {:keys [handler-fn topics]}]
                       (assoc acc proc-name
                              (tp/start {:event-pubsub ps :topics topics
                                         :handler-fn handler-fn :context base-ctx})))
                     {} @tp/processor-registry*)]
     (assoc base-ctx :processors processors))))

(defn- stop-ctx [ctx]
  (doseq [[_ p] (:processors ctx)] (tp/stop p))
  (rmp/l1-clear!)
  (when-let [ps (:event-pubsub ctx)] (pubsub/stop ps))
  (when-let [c (:cache ctx)] (kv/stop c))
  (when-let [s (:event-store ctx)] (es/stop s))
  (when-let [d (::cache-dir ctx)]
    (let [f (java.io.File. d)]
      (when (.exists f) (doseq [c (.listFiles f)] (.delete c)) (.delete f))))
  ;; GC-4 — delete the SQLite db-file + its WAL/SHM sidecars (same cleanup the
  ;; sqlite-ctx precedent does), so a persistent build leaves no disk residue.
  (when-let [f (::db-file ctx)]
    (doseq [s ["" "-wal" "-shm"]]
      (let [file (java.io.File. (str f s))]
        (when (.exists file) (.delete file))))))

;; =============================================================================
;; Read-back analysis (schema/predicate-AGNOSTIC — reads whatever the builder
;; produced). Verbatim from V17 so the stats are directly comparable.
;; =============================================================================

(defn- snapshot [ctx oid]
  {:concepts (rm/get-concepts ctx {:ontology-id oid})
   :relationships (filter #(= oid (:ontology-id %)) (rm/get-relationships ctx))})

(defn uri-kind
  "Convention-agnostic scheme classifier. The scheme is the substring before the
   FIRST occurrence of `:` OR `/` (whichever comes first). Handles both the
   canonical slash form (`institution/236753` → :institution) and the
   legacy/degraded colon form (`degree_program:22:52` → :degree_program). A URI
   with neither separator → :other. Pure structural string logic — names no
   domain (GC-3 Discipline 12)."
  [uri]
  (let [u (str uri)
        ci (str/index-of u ":")
        si (str/index-of u "/")
        i (cond (and ci si) (min ci si)
                ci ci
                si si
                :else nil)]
    (if i (keyword (str/lower-case (subs u 0 i))) :other)))

(defn- uri-scheme+identity
  "Split a URI into [scheme identity-tail] at the FIRST `:` OR `/` separator.
   Returns nil when the URI has no separator (no scheme to compare). Pure
   structural string logic — names no domain."
  [uri]
  (let [u (str uri)
        ci (str/index-of u ":")
        si (str/index-of u "/")
        i (cond (and ci si) (min ci si)
                ci ci
                si si
                :else nil)]
    (when i
      [(keyword (str/lower-case (subs u 0 i)))
       (subs u (inc i))])))

(defn graph-health
  "Pure graph-health fragmentation check over a concept collection. Flags the
   structural signature of the GC-1 fragmentation: the SAME identifying value
   (the identity-tail after the first `:`/`/` separator) appearing under ≥2
   DISTINCT schemes (e.g. CIP `01.0901` minted as both `programofstudy/01.0901`
   and `degree_program:…:01.0901`). Reports each offending identity with its
   schemes + the per-scheme concept count. A clean single-scheme graph flags
   nothing. Domain-agnostic — no baked field names or entity literals.

   DIAGNOSTIC, not a sole pass/fail gate: the tail-collision signal is over-
   sensitive — two genuinely DISTINCT entity types that reuse a bare local key
   (e.g. `institution/22` and `field/22`) collide on tail `22` and read as
   `:fragmented? true` even though they are NOT the same entity. It correctly
   flags REAL fragmentation (same identifying value under synonym type-names, e.g.
   `field_of_study/47` ≡ `field/47`), but a precise acceptance gate (GC-5) should
   combine this with the canonicalization degraded-count + a label-equivalence
   check, not consume this boolean alone. The deeper root cause it surfaces —
   per-source model-specs naming the same entity-type differently — is fixed at
   source by the shared discovered vocabulary (GC-6), not by this detector."
  [concepts]
  (let [by-identity (->> concepts
                         (keep (fn [c] (when-let [[scheme tail] (uri-scheme+identity (:uri c))]
                                         [tail scheme])))
                         (group-by first))
        fragmented (->> by-identity
                        (keep (fn [[tail pairs]]
                                (let [scheme-counts (frequencies (map second pairs))]
                                  (when (>= (count scheme-counts) 2)
                                    {:identity tail
                                     :schemes (vec (keys scheme-counts))
                                     :scheme-counts scheme-counts
                                     :count (count pairs)}))))
                        (sort-by (comp - :count))
                        vec)]
    {:fragmented? (boolean (seq fragmented))
     :fragmented-identity-count (count fragmented)
     :fragmented-identities fragmented}))

(defn graph-stats [ctx oid]
  (let [{:keys [concepts relationships]} (snapshot ctx oid)
        concept-uris (set (map :uri concepts))
        by-kind (frequencies (map #(uri-kind (:uri %)) concepts))
        by-pred (frequencies (map :predicate relationships))
        kind-of (into {} (map (juxt :uri #(uri-kind (:uri %))) concepts))
        cross-source (->> relationships
                          (filter (fn [r] (let [sk (kind-of (:source-uri r))
                                                tk (kind-of (:target-uri r))]
                                            (and sk tk (not= sk tk)))))
                          (map (fn [r] [(kind-of (:source-uri r)) (:predicate r) (kind-of (:target-uri r))]))
                          frequencies)
        dangling (->> relationships
                      (remove (fn [r] (and (concept-uris (:source-uri r)) (concept-uris (:target-uri r)))))
                      (mapv #(select-keys % [:source-uri :target-uri :predicate])))
        axioms (try (rm/get-axioms ctx oid) (catch Throwable _ nil))
        with-attrs (filter #(seq (:attributes %)) concepts)
        earnings-bearing (filter (fn [c] (some (fn [[k _]] (re-find #"(?i)earn|wage" (name k))) (:attributes c))) concepts)]
    {:concept-count (count concepts)
     :relationship-count (count relationships)
     :concepts-by-kind by-kind
     :relationships-by-predicate by-pred
     :cross-source-links cross-source
     :cross-source-link-total (reduce + 0 (vals cross-source))
     :every-edge-endpoint-resolves (and (seq relationships) (empty? dangling))
     :dangling-edge-count (count dangling)
     :sample-dangling-edges (vec (take 8 dangling))
     :concepts-with-attributes (count with-attrs)
     :earnings-or-wage-bearing-concepts (count earnings-bearing)
     :graph-health (graph-health concepts)
     :axiom-count (if (map? axioms)
                    (reduce + 0 (map (fn [[_ v]] (cond (sequential? v) (count v) (map? v) (count v)
                                                       (set? v) (count v) :else 0)) axioms)) 0)}))

(defn- edges-from [rels uri] (filter #(= uri (:source-uri %)) rels))
(defn- edges-to   [rels uri] (filter #(= uri (:target-uri %)) rels))

(defn- guess-kinds [by-kind]
  (let [kinds (set (keys by-kind))
        pick (fn [& subs] (some (fn [k] (when (some #(str/includes? (name k) %) subs) k)) kinds))]
    {:program (pick "program" "prog") :cip (pick "cip" "field" "discipline")
     :soc (pick "soc" "occ" "onet") :institution (pick "unitid" "inst" "ipeds" "opeid")
     :earnings (pick "pseo" "earn" "wage")}))

(defn connectivity-proof [ctx oid]
  (let [{:keys [concepts relationships]} (snapshot ctx oid)
        by-uri (into {} (map (juxt :uri identity) concepts))
        kind-of (fn [u] (uri-kind u))
        roles (guess-kinds (frequencies (map #(uri-kind (:uri %)) concepts)))
        programs (filter #(= (:program roles) (kind-of (:uri %))) concepts)
        chain (some
               (fn [prog]
                 (let [p-uri (:uri prog)
                       cip-edge (first (filter #(= (:cip roles) (kind-of (:target-uri %))) (edges-from relationships p-uri)))
                       cip-uri (:target-uri cip-edge)
                       soc-edge (when cip-uri (first (filter #(= (:soc roles) (kind-of (:target-uri %))) (edges-from relationships cip-uri))))
                       soc-uri (:target-uri soc-edge)]
                   (when (and cip-uri soc-uri)
                     {:program (select-keys prog [:uri :label :attributes])
                      :program->cip (select-keys cip-edge [:source-uri :predicate :target-uri])
                      :cip (select-keys (get by-uri cip-uri) [:uri :label])
                      :cip->soc (select-keys soc-edge [:source-uri :predicate :target-uri])
                      :soc (select-keys (get by-uri soc-uri) [:uri :label :attributes])})))
               programs)]
    (or chain {:no-complete-chain true :roles-detected roles :program-count (count programs)
               :note "No program->field->occupation chain — see cross-source-links for where it broke."})))

(defn earnings-to-program-verdict [ctx oid]
  (let [{:keys [concepts relationships]} (snapshot ctx oid)
        kind-of (fn [u] (uri-kind u))
        roles (guess-kinds (frequencies (map #(uri-kind (:uri %)) concepts)))
        earn-uris (set (map :uri (filter #(= (:earnings roles) (kind-of (:uri %))) concepts)))
        earn-edges (->> relationships
                        (filter (fn [r] (or (earn-uris (:source-uri r)) (earn-uris (:target-uri r)))))
                        (map (fn [r] {:source-kind (kind-of (:source-uri r)) :predicate (:predicate r)
                                      :target-kind (kind-of (:target-uri r))})))
        earn-other-kinds (set (mapcat (fn [e] [(:source-kind e) (:target-kind e)]) earn-edges))
        program-side (into #{} (remove nil? [(:program roles) (:institution roles) (:cip roles)]))
        connects-to (set/intersection earn-other-kinds program-side)]
    {:earnings-concept-count (count earn-uris)
     :earnings-edge-count (count earn-edges)
     :earnings-edges-by-link (frequencies (map (juxt :source-kind :predicate :target-kind) earn-edges))
     :earnings-connects-to-program-side? (boolean (seq connects-to))
     :connects-to-kinds connects-to}))

(defn retrievability-probes! [ctx oid]
  (into {} (for [q ["psychology bachelor's degree" "social work program"
                    "registered nurse occupation" "computer science engineering"
                    "clinical psychologist earnings"]]
             [q (->> (ontology/hybrid-search ctx {:query-text q :ontology-ids [oid] :limit 5})
                     :results (mapv (fn [r] {:uri (:uri r) :label (:label r) :score (:score r)})))])))

;; =============================================================================
;; GC-5 — the ACCEPTANCE VERDICT (pure, TDD-able on fixtures).
;;
;; Takes the captured graph analysis the driver produces (`:status`, `:stats`
;; with `:concepts-by-kind` + `:graph-health`, and `:connectivity`) and returns
;; an explicit {:pass? bool :reasons [...]} over the GC-5 criteria. Each reason
;; is {:criterion kw :pass? bool :detail str}. PURE — no I/O, no LLM; reads only
;; the captured map. This is the guard against "how could this pass while still
;; being wrong?": a fragmented / 0-draft / no-chain build MUST read :pass? false.
;; =============================================================================

(defn- honest-terminal?
  "An honest terminal status: a real CQ verdict, NOT a crash/timeout/fabrication.
   `:complete` (CQ satisfied) and `:failed-cq` (a DIAGNOSED honest terminal — the
   loop ran to a real verdict that the CQs were not met) both count. `:timeout`,
   `:error`, nil, or an analysis-error marker do NOT."
  [status]
  (boolean (#{:complete :failed-cq} status)))

(defn acceptance-verdict
  "GC-5 PASS/FAIL over the captured graph analysis. `captured` is the map `run!`
   returns (or a synthetic fixture of the same shape). Returns
   {:pass? bool :reasons [{:criterion :detail :pass?}...]}. :pass? is the AND of
   every criterion. Criteria:
     :honest-terminal  — :status is :complete or :failed-cq (not crash/timeout)
     :non-zero-build   — concept-count > 0 AND at least one kind has drafts
     :one-connected-graph — GC-3 :graph-health/:fragmented? is false
     :chain-reads-back — :connectivity is a real program→cip→soc chain (NOT
                         {:no-complete-chain true})
     :convention-agnostic-kinds — institutions ≫ 1 AND occupations present in
                         concepts-by-kind (the real kinds, not the mis-measured
                         :other read)."
  [{:keys [status stats connectivity] :as _captured}]
  (let [by-kind        (:concepts-by-kind stats)
        concept-count  (:concept-count stats)
        fragmented?    (get-in stats [:graph-health :fragmented?])
        ;; institutions ≫ 1: a kind whose name carries inst/unitid/ipeds/opeid
        inst-count     (->> by-kind
                            (filter (fn [[k _]] (re-find #"(?i)inst|unitid|ipeds|opeid" (name k))))
                            (map second) (reduce + 0))
        ;; occupations present: a kind whose name carries soc/occ/onet
        occ-count      (->> by-kind
                            (filter (fn [[k _]] (re-find #"(?i)soc|occ|onet" (name k))))
                            (map second) (reduce + 0))
        no-chain?      (boolean (:no-complete-chain connectivity))
        criteria
        [{:criterion :honest-terminal
          :pass? (honest-terminal? status)
          :detail (str "status=" status (when-not (honest-terminal? status)
                                           " (NOT :complete/:failed-cq — crash/timeout/fake)"))}
         {:criterion :non-zero-build
          :pass? (boolean (and (number? concept-count) (pos? concept-count)
                               (some (fn [[_ v]] (pos? (long v))) by-kind)))
          :detail (str "concept-count=" concept-count " kinds=" (count by-kind))}
         {:criterion :one-connected-graph
          :pass? (false? fragmented?)
          :detail (str ":graph-health/:fragmented?=" fragmented?
                       (when fragmented? " — same-label-different-canonical-type split present"))}
         {:criterion :chain-reads-back
          :pass? (and (map? connectivity) (not no-chain?)
                      (some? (:program connectivity)) (some? (:soc connectivity)))
          :detail (if no-chain?
                    ":no-complete-chain true — program→cip→soc did NOT read back"
                    (str "chain: " (get-in connectivity [:program :uri])
                         " → " (get-in connectivity [:cip :uri])
                         " → " (get-in connectivity [:soc :uri])))}
         {:criterion :convention-agnostic-kinds
          :pass? (and (> (long inst-count) 1) (pos? (long occ-count)))
          :detail (str "institutions=" inst-count " occupations=" occ-count)}]]
    {:pass? (every? :pass? criteria)
     :reasons criteria}))

;; =============================================================================
;; Orchestrator — ONE greenfield run-central-evolver! over all 5 sources (the
;; greenfield arm processes each source against the accumulating graph, so EB5
;; reconcile links across sources within the single pass).
;; =============================================================================

(defn run!
  [{:keys [model budget evolver-config only store max-containers max-windows]
    :or {model default-model
         budget {:max-iterations 16 :total-budget-ms 600000 :max-retries 3}
         evolver-config {:max-iterations 3}
         ;; GC-4 — the comprehensive build runs on the PERSISTENT SQLite store by
         ;; default (event log on disk, not heap). A caller can pass :in-memory.
         store :sqlite
         ;; GC-9 — the reduced-cap knobs (default nil → the extract uses its own
         ;; defaults 25/50 — behavior-preserving). A caller passes e.g.
         ;; {:max-containers 6 :max-windows 5} for a bounded reduced-cap build that
         ;; fits in heap (the default-cap full build OOMs at ~148k drafts/source).
         max-containers nil
         max-windows nil}}]
  (when-not (System/getenv "OPENROUTER_API_KEY")
    (throw (ex-info "OPENROUTER_API_KEY env var required (env only)" {})))
  (register-openrouter! model)
  (let [ctx (make-ctx {:store store})
        oid (random-uuid)
        chosen (cond->> (sources) only (filter #(some #{(:name %)} only)))
        srcs (mapv #(select-keys % [:type :path]) chosen)]
    (try
      (println "=== EB12 GRAPH B — CURRENT ARCHITECTURE (central evolver) ===")
      (println "Ontology-id:" oid "  model:" model)
      (println "Sources:" (mapv :name chosen))
      (println "Budget/source:" budget "  evolver-config:" evolver-config)
      (println "Caps (GC-9):" {:max-containers max-containers :max-windows max-windows}
               (if (or max-containers max-windows) "(REDUCED-CAP build)" "(default 25/50)"))
      (let [safe (fn [label f] (try (f) (catch Throwable t
                                                (println "    [analysis ERROR]" label (.getMessage t))
                                                {:analysis-error (str label ": " (.getMessage t))})))
            t0 (System/currentTimeMillis)
            result (ce/run-central-evolver!
                    ctx {:ontology-id oid
                         :sources srcs
                         :goal domain-goal
                         :model model
                         :judge-fn real-llm-judge
                         :resilient? true        ; EB9 — recover-or-fail-with-diagnosis per source
                         :budget budget
                         :evolver-config evolver-config
                         ;; GC-9 — the reduced-cap knobs (default nil → extract defaults).
                         ;; A bounded reduced-cap build passes {:max-containers 6
                         ;; :max-windows 5} to fit the connectivity proof in heap.
                         :max-containers max-containers
                         :max-windows max-windows})
            elapsed (- (System/currentTimeMillis) t0)
            _ (println "  central evolver status:" (:status result) " mode:" (:mode result) "(" elapsed "ms)")
            _ (println "  termination:" (get-in result [:cq-loop :termination-reason]))
            ;; On a non-:complete status, dump the evolver's own diagnostics so we
            ;; see WHICH step/source failed (don't lose it to a post-processing crash).
            _ (when-not (= :complete (:status result))
                (println "  !! NON-COMPLETE — evolver diagnostics:")
                (pp/pprint (select-keys result [:status :mode :failed-at :error :failed-step
                                                :failed-source :branch-points])))
            _ (println ">>> graph-structure stats") stats (safe "graph-stats" #(graph-stats ctx oid))
            _ (println "    concepts:" (:concept-count stats) " rels:" (:relationship-count stats)
                       " cross-source:" (:cross-source-link-total stats)
                       " dangling:" (:dangling-edge-count stats)
                       " earnings/wage:" (:earnings-or-wage-bearing-concepts stats))
            _ (println ">>> connectivity proof") conn (safe "connectivity" #(connectivity-proof ctx oid))
            _ (println "    " (if (:no-complete-chain conn) "NO COMPLETE CHAIN" "CHAIN FOUND"))
            _ (println ">>> earnings->program verdict") earn (safe "earnings" #(earnings-to-program-verdict ctx oid))
            _ (println ">>> retrievability probes") probes (safe "probes" #(retrievability-probes! ctx oid))
            snap (snapshot ctx oid)]
        (println "=== DONE ===")
        {:ontology-id oid :model model :budget budget :evolver-config evolver-config
         :sources (mapv :name chosen) :elapsed-ms elapsed
         :status (:status result) :mode (:mode result)
         :branch-points (:branch-points result)
         :survey-profiles (:survey-profiles result)
         :competency-questions (:competency-questions result)
         :graph-health (:graph-health result) :cq-verdict (:cq-verdict result)
         :cq-loop (:cq-loop result) :build-result (:build-result result)
         :stats stats :connectivity conn :earnings-verdict earn :probes probes
         ::concepts (:concepts snap) ::relationships (:relationships snap)})
      (finally (stop-ctx ctx)))))

;; =============================================================================
;; Capture + artifact (A2-vs-B comparable)
;; =============================================================================

(defn save-artifact! [r]
  (io/make-parents artifact-path)
  (spit artifact-path
        (pr-str {:ontology-id (:ontology-id r) :model (:model r)
                 :status (:status r) :mode (:mode r)
                 :concepts (mapv #(select-keys % [:uri :label :description :scope :indicators :attributes :broader]) (::concepts r))
                 :relationships (mapv #(select-keys % [:source-uri :target-uri :predicate :confidence-class]) (::relationships r))
                 :stats (:stats r)}))
  artifact-path)

(defn print-summary! [r]
  (println "\n================ EB12 GRAPH B (central evolver) ================")
  (println "ontology-id:" (:ontology-id r) " status:" (:status r) " mode:" (:mode r) "(" (:elapsed-ms r) "ms)")
  (println "\n--- graph stats ---") (pp/pprint (:stats r))
  (println "\n--- connectivity proof ---") (pp/pprint (:connectivity r))
  (println "\n--- earnings->program verdict ---") (pp/pprint (:earnings-verdict r))
  (println "\n--- CQ loop ---")
  (println "  termination:" (get-in r [:cq-loop :termination-reason]) " iterations:" (get-in r [:cq-loop :iterations]))
  (println "\n--- retrievability ---") (pp/pprint (:probes r)))

(defn save-capture! [r]
  (save-artifact! r)
  (io/make-parents capture-path)
  (spit capture-path
        (str "# EB12 — Graph B via the CURRENT architecture (central evolver) — LIVE VERIFY\n\n"
             "**Branch:** `feature/ontology-architecture`. **No mocks** — real Grain, real "
             "OpenRouter (`" (:model r) "`), real local embeddings (all-MiniLM-L6-v2), real "
             "ColBERT. The composed central evolver (`run-central-evolver!`) `:delegate`s the "
             "EB2-EB9 subbehaviors; CQ-gate in-process with the real judge.\n\n"
             "Ontology-id: `" (:ontology-id r) "`. Sources: " (pr-str (:sources r)) ".\n"
             "Status: **" (:status r) "** mode **" (:mode r) "** (" (:elapsed-ms r) " ms). "
             "Budget/source: `" (pr-str (:budget r)) "`.\n\n"
             "## The no-hardcoding audit — the only per-source text is the DOMAIN GOAL\n\n```\n"
             domain-goal "```\n\n"
             "## Graph-structure stats (A2-vs-B comparable)\n\n```clojure\n"
             (with-out-str (pp/pprint (:stats r))) "```\n\n"
             "## Connectivity proof (multi-hop read-back)\n\n```clojure\n"
             (with-out-str (pp/pprint (:connectivity r))) "```\n\n"
             "## Earnings→program verdict (MEASURED OUTCOME)\n\n```clojure\n"
             (with-out-str (pp/pprint (:earnings-verdict r))) "```\n\n"
             "## CQ verdict + loop trace (the OBJECTIVE)\n\n```clojure\n"
             (with-out-str (pp/pprint {:cq-verdict (:cq-verdict r)
                                       :graph-health (:graph-health r)
                                       :cq-loop (:cq-loop r)})) "```\n\n"
             "## Retrievability — labeled hybrid-search hits\n\n```clojure\n"
             (with-out-str (pp/pprint (:probes r))) "```\n"))
  (println "Capture written:" capture-path)
  (println "Artifact written:" artifact-path)
  capture-path)

(defn -main [& _]
  (let [fut (future
              (try (let [r (run! {})] (print-summary! r) (save-capture! r)
                     (if (#{:complete :failed-cq} (:status r)) :done :error))
                   (catch Throwable t
                     (println "EB12 graph-B build FAILED:" (.getMessage t))
                     (.printStackTrace t) :error)))
        result (deref fut 3300000 :timeout)]  ; 55-min ceiling
    (println "\nEB12 graph-B build result:" result)
    (shutdown-agents)
    (System/exit (if (= :done result) 0 1))))

;; =============================================================================
;; DEBUG — trace the derive-cqs failure to root cause (survey profiles + derive).
;; Reproduces ONLY survey -> derive-cqs on a chosen source set, dumping the
;; runtime values the evolver hides (profile content + the derive result/error).
;; =============================================================================

(defn debug-survey-derive!
  "Survey the chosen sources ONCE, then run derive-cqs N times each with
   resilient? false and true on the SAME profiles, to isolate the DERIVE +
   EB9-resilience reliability from survey variance."
  [{:keys [model only n] :or {model default-model only [:crosswalk :wages] n 4}}]
  (register-openrouter! model)
  (let [ctx (make-ctx)
        chosen (filter #(some #{(:name %)} only) (sources))]
    (try
      (println "=== DEBUG survey->derive for" (mapv :name chosen) " n=" n "each ===")
      (let [surveys (mapv (fn [src]
                            (let [r (ce/delegate-survey! ctx {:source (select-keys src [:type :path])
                                                              :goal domain-goal :model model})]
                              (println ">>> SURVEY" (:name src) "status:" (:status r)
                                       " profile-keys:" (when (map? (:profile r)) (keys (:profile r))))
                              r))
                          chosen)
            profiles (mapv :profile surveys)
            trial (fn [resilient?]
                    (let [d (ce/delegate-derive-cqs! ctx {:ontology-id (random-uuid) :goal domain-goal
                                                          :profile profiles :consumer-cqs nil
                                                          :model model :resilient? resilient?})]
                      {:status (:status d) :cq-count (count (:competency-questions d)) :error (:error d)}))
            run-batch (fn [resilient?]
                        (println "\n>>> DERIVE x" n " resilient?=" resilient?)
                        (let [rs (mapv (fn [i] (let [t (trial resilient?)]
                                                 (println (format "    trial %d: status=%s cq-count=%d%s"
                                                                  i (:status t) (:cq-count t)
                                                                  (if (:error t) (str " ERR=" (:error t)) "")))
                                                 t))
                                       (range n))]
                          {:resilient? resilient? :trials rs
                           :success (count (filter #(= :success (:status %)) rs))
                           :nonempty (count (filter #(pos? (:cq-count %)) rs))}))
            non-res (run-batch false)
            res (run-batch true)]
        (println "\n=== SUMMARY ===")
        (println "  resilient?=false :" (:success non-res) "/" n "success," (:nonempty non-res) "/" n "non-empty CQs")
        (println "  resilient?=true  :" (:success res) "/" n "success," (:nonempty res) "/" n "non-empty CQs")
        {:non-resilient non-res :resilient res})
      (finally (stop-ctx ctx)))))

(defn debug-model-extract!
  "Reproduce the per-source Model->Extract pipeline on ONE source (default IPEDS
   SQL — the one that fails) and dump WHERE it breaks: did Model produce a
   model-spec? did Extract produce drafts? Isolates the failing node."
  [{:keys [model only resilient?] :or {model default-model only :ipeds resilient? true}}]
  (register-openrouter! model)
  (let [ctx (make-ctx)
        src (first (filter #(= only (:name %)) (sources)))
        {:keys [pipeline-sheet-id]} (ce/register-pipeline-sheets! ctx {:model model :resilient? resilient?})]
    (try
      (println "=== DEBUG model-extract for" (:name src) "(" (:type src) ") resilient?=" resilient? "===")
      (let [sv (ce/delegate-survey! ctx {:source (select-keys src [:type :path]) :goal domain-goal :model model})
            _ (println ">>> SURVEY status:" (:status sv) " profile-keys:" (when (map? (:profile sv)) (keys (:profile sv))))
            mx (ce/delegate-model-extract! ctx {:source (select-keys src [:type :path])
                                                :goal domain-goal :profile (:profile sv)
                                                :pipeline-sheet-id pipeline-sheet-id :model model})]
        (println "\n=== MODEL-EXTRACT RESULT ===")
        (println "  status:" (:status mx) "  error:" (:error mx))
        (println "  model-spec present?:" (boolean (:model-spec mx))
                 " keys:" (when (map? (:model-spec mx)) (keys (:model-spec mx))))
        (println "  candidate-axioms present?:" (boolean (:candidate-axioms mx)))
        (println "  concept-drafts count:" (count (:concept-drafts mx)))
        (println "  relationship-drafts count:" (count (:relationship-drafts mx)))
        (println "  extraction-report:" (pr-str (:extraction-report mx)))
        (println "  model-spec (verbatim):")
        (pp/pprint (:model-spec mx))
        mx)
      (finally (stop-ctx ctx)))))

(defn debug-extract-internals!
  "Run the Extract sheet ALONE on one source with capture of its INTERNAL
   blackboard: the authored transform SOURCE + the apply extraction-report — the
   keys hidden behind :delegate. resilient? false so nothing masks the raw result."
  [{:keys [model only resilient?] :or {model default-model only :ipeds resilient? false}}]
  (register-openrouter! model)
  (let [ctx (make-ctx)
        src (first (filter #(= only (:name %)) (sources)))
        sdesc (select-keys src [:type :path])
        reg-pipeline (requiring-resolve 'ai.obney.orc.ontology.core.central-evolver/register-pipeline-sheets!)
        reg-extract  (requiring-resolve 'ai.obney.orc.ontology.core.extract-subbehavior/register-extract-subbehavior!)
        dsl-execute  (requiring-resolve 'ai.obney.orc.orc-service.interface/execute)
        dsl-bb       (requiring-resolve 'ai.obney.orc.orc-service.interface/get-tick-blackboard)
        {:keys [pipeline-sheet-id]} (reg-pipeline ctx {:model model :resilient? resilient?})]
    (try
      (let [sv (ce/delegate-survey! ctx {:source sdesc :goal domain-goal :model model})
            mx (ce/delegate-model-extract! ctx {:source sdesc :goal domain-goal :profile (:profile sv)
                                                :pipeline-sheet-id pipeline-sheet-id :model model})
            model-spec (:model-spec mx)
            extract-sid (reg-extract ctx {:model model :resilient? resilient?})
            tick-id (random-uuid)
            _ (println "=== executing Extract ALONE on" (:name src) " resilient?=" resilient? "===")
            result (dsl-execute ctx extract-sid {"model-spec" model-spec "source" sdesc}
                                :tick-id tick-id :timeout-ms 180000)
            bb (dsl-bb ctx tick-id)
            trunc (fn [v] (let [s (pr-str v)] (if (> (count s) 1400) (str (subs s 0 1400) " …[truncated]") s)))]
        (println "extract tree status:" (:status result) " error:" (:error result))
        (println "blackboard keys:" (keys bb))
        (doseq [k (keys bb)]
          (println "\n---" k "---")
          (println (trunc (get-in bb [k :value]))))
        result)
      (finally (stop-ctx ctx)))))

(comment
  (require '[eb12-graph-b-central-evolver :as b] :reload)
  (def r (b/run! {}))
  (b/print-summary! r)
  (b/save-capture! r))
