(ns v17-graph-b-full-scale
  "V17 — Graph B full-scale rebuild (AUTONOMOUS discovery).

   This is a LIVE build that DOUBLES AS A TEST of the recursive-RLM
   evolutionary builder. Unlike V09 — whose per-source prompts hand-fed table
   names, column indices, row offsets, LIMIT values, and explicit mint/join
   recipes — V17 hands the builder ONLY:

     1. the 5 official sources (path + format),
     2. the per-format source-exploration tools (granted automatically by
        run-discovery! / the V06 source-tool registry), and
     3. the DOMAIN GOAL (a goal, not a recipe).

   The builder EXPLORES each source, DISCOVERS its keys/crosswalks, decides
   which entities are the same real-world thing, mints shared canonical URIs so
   they MERGE, and designs the extraction. Any cross-source link that appears in
   graph B is the BUILDER'S discovery, not the driver's instruction.

   THE LOAD-BEARING RULE (auditable): the prompt this driver hands the builder
   contains NO hardcoded joins, key names, column names/indices, table names,
   row offsets, LIMIT values, filter clauses, or crosswalk recipes. The only
   per-source text is the DOMAIN GOAL (identical for every source) prepended to
   the shipped `default-discovery-prompt`. The shipped prompt's per-format
   exploration guidance (tables→classes, the offset-paging affordance, the
   cross-source-linking principle) is format MECHANICS the platform ships — it
   names no domain table/column/key. The exact assembled prompts are captured so
   the no-hardcoding rule is auditable.

   THE 5 OFFICIAL SOURCES (path + format ONLY — contents are the builder's to
   discover):
     1. IPEDS      — SQLite output.db
     2. crosswalk  — CSV cip_soc_crosswalk.csv
     3. O*NET      — Excel DIRECTORY db_30_1_excel (folder of workbooks)
     4. LA-OEWS    — CSV louisiana_occupation_wages.csv
     5. PSEO       — Excel pseo_la.xlsx

   THE DOMAIN GOAL (a goal, NOT a recipe): build a comprehensive, connected
   ontology over the LA education-and-career sources — programs, fields of
   study, occupations, institutions, earnings/wages — merging same-real-world
   entities by minting the same canonical id, finding the sources' own shared
   keys/crosswalks to connect.

   EARNINGS→PROGRAM is a MEASURED OUTCOME, not a task. V09 left earnings
   (one institution-id encoding) and programs (another) disjoint. Whether the
   builder bridges them is OBSERVED here; the driver names no key, adds no
   driver-side join. Reported honestly either way.

   SCALE: comprehensive coverage is the builder's own retrieval decision (it
   pages the sources via the per-format :offset affordance — including the SQL
   :offset paging added for this slice). The deterministic transforms it designs
   run over full results at no per-row LLM cost. Generous budget. 4 GB LMDB
   map-size (the 10 MB default MapFull-crashes at scale). Same local embedding
   model (all-MiniLM-L6-v2, 384-dim) + ColBERT config A1 uses (fairness).

   No mocks. Real Grain event store, real LLM discovery, real local embeddings,
   real ColBERT. No false green — a disconnected graph, missed earnings bridge,
   or under-coverage is reported AS-IS, never patched with hand-holding.

   USAGE (REPL with :dev:test, OPENROUTER_API_KEY in env ONLY):
     (require '[v17-graph-b-full-scale :as v17])
     (def r (v17/run! {}))
     (v17/print-summary! r)
     (v17/save-capture! r)"
  (:require [ai.obney.orc.ontology.interface :as ontology]
            [ai.obney.orc.ontology.interface.schemas]
            [ai.obney.orc.ontology.core.commands]
            [ai.obney.orc.ontology.core.read-models :as rm]
            [ai.obney.orc.ontology.core.rlm-discovery :as rlm-discovery]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.query-processor.interface :as qp]
            [ai.obney.grain.pubsub.interface :as pubsub]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp]
            [ai.obney.orc.orc-service.core.todo-processors]
            [ai.obney.grain.todo-processor-v2.interface :as tp]
            [litellm.router :as litellm-router]
            [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.pprint :as pp]))

;; =============================================================================
;; The 5 official sources (PATH + FORMAT ONLY — no contents fed)
;; =============================================================================

(def ipeds-db    "/Users/darylroberts/Downloads/output.db")
(def crosswalk-csv "/Users/darylroberts/Downloads/cip_soc_crosswalk.csv")
(def onet-dir    "/Users/darylroberts/Downloads/db_30_1_excel")
(def wages-csv   "/Users/darylroberts/Desktop/Code/area_51/dspy_notebooks/bryc-workshop/components/recommendations/resources/recommendations/louisiana_occupation_wages.csv")
(def pseo-xlsx   "/Users/darylroberts/Downloads/pseo_la.xlsx")

(def default-model "google/gemini-3-flash-preview")

(def capture-path "docs/build-timeline/live-verify/V17-graph-b-full-scale.md")
(def artifact-path "docs/build-timeline/live-verify/V17-graph-b-full-scale-artifact.edn")

(defn sources []
  [{:name :ipeds      :type :sql   :path ipeds-db}
   {:name :crosswalk  :type :csv   :path crosswalk-csv}
   {:name :onet       :type :excel :path onet-dir}     ; the DIRECTORY, builder discovers the workbook
   {:name :wages      :type :csv   :path wages-csv}
   {:name :pseo       :type :excel :path pseo-xlsx}])

;; =============================================================================
;; THE DOMAIN GOAL — the ONLY per-source text the driver supplies.
;;
;; A goal, NOT a recipe. It names NO table, column, index, offset, LIMIT, key,
;; or join. It is identical for every source. This is the entirety of the
;; driver's "hand" to the builder, prepended to the shipped default discovery
;; prompt (which carries the OUTPUT SHAPE + grounding discipline + per-format
;; exploration MECHANICS). The cross-source-linking PRINCIPLE (mint shareable
;; code-system URIs so concepts merge) is part of the shipped prompt mechanics,
;; not a domain key the driver names.
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
   "This is ONE source of several that together form the connected graph; mint "
   "your concepts so they will link up with the others by shared canonical id.\n\n"
   "============================================================\n\n"))

(defn discovery-prompt-for
  "The EXACT discovery prompt the builder receives for a source: the DOMAIN GOAL
   (identical for every source) prepended to the shipped default-discovery-prompt.
   No per-source hints, no keys, no joins. Captured verbatim for the audit. (The
   shipped run-discovery! further PREPENDS the per-format exploration mechanics
   for a structured source; those name no domain key — see the capture's
   no-hardcoding audit.)"
  [_source]
  (str domain-goal rlm-discovery/default-discovery-prompt))

;; =============================================================================
;; Provider + context wiring (mirrors v02_mode_a / v09)
;; =============================================================================

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
        dir (str "/tmp/v17-graph-b-" (random-uuid))
        ;; NON-NEGOTIABLE: default 10 MB LMDB map-size MapFull-crashes at real
        ;; scale (>2.5K concepts x 384-dim embeddings). 4 GB per V02/V16.
        cache (kv/start (lmdb/->KV-Store-LMDB
                         {:storage-dir dir :db-name "graph-b"
                          :map-size (* 4 1024 1024 1024)}))
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

;; =============================================================================
;; Per-source discovery (one source per session — V06 pattern, shared graph)
;; =============================================================================

(defn- snapshot [ctx oid]
  (let [concepts (filter #(= oid (:ontology-id %)) (rm/get-concepts ctx {}))
        rels (filter #(= oid (:ontology-id %)) (rm/get-relationships ctx))]
    {:concepts concepts :relationships rels}))

(defn discover-source!
  "Run autonomous discovery for ONE source against the shared ontology-id.
   Captures the model's drafts + reasoning trace VERBATIM, then compiles them
   into events. The prompt is the DOMAIN GOAL + default prompt only."
  [ctx oid {:keys [name] :as source} model budget]
  (let [before (snapshot ctx oid)
        prompt (discovery-prompt-for source)
        disc (ontology/run-discovery!
              ctx {:ontology-id oid
                   :sources [source]
                   :discovery-prompt prompt
                   :model model
                   :budget budget})
        ;; HARNESS ROBUSTNESS (root-caused in the V17 run): compile-discovery-
        ;; source! validates every draft and throws LOUDLY on a malformed one
        ;; (correct — no silent drop). But for a multi-source autonomous build,
        ;; one source emitting a single malformed draft should NOT lose the other
        ;; sources' already-committed work nor abort the whole run + capture. We
        ;; ISOLATE each source's compile: a compile failure is recorded as THAT
        ;; source's honest outcome (with the offending draft captured verbatim),
        ;; the loud error is preserved in the capture, and the build proceeds.
        ;; This does NOT weaken validation and does NOT silently drop a bad draft
        ;; — the source that emitted it lands ZERO concepts and is flagged.
        compiled (when (= :emitted-drafts (:status disc))
                   (try
                     (ontology/compile-discovery-source! ctx oid disc)
                     (catch Throwable t
                       {:status :failed-at-compile
                        :error (.getMessage t)
                        :offending-draft (:draft (ex-data t))
                        :ex-data (ex-data t)})))
        after (snapshot ctx oid)
        new-concept-uris (set/difference (set (map :uri (:concepts after)))
                                         (set (map :uri (:concepts before))))]
    {:source name
     :prompt-handed prompt
     :discovery-status (:status disc)
     :compile-status (or (:status compiled) (:status (:discovery-provenance compiled)))
     :compile-error (:error compiled)
     :offending-draft (:offending-draft compiled)
     :emitted-concepts (count (:emitted-concepts disc))
     :emitted-relationships (count (:emitted-relationships disc))
     :emitted-axioms (count (:emitted-axioms disc))
     :compiled-provenance (:discovery-provenance compiled)
     :rlm-trace (:rlm-trace disc)
     :iteration-reasonings (:iteration-reasonings disc)
     :usage (:usage disc)
     :session-error (:error disc)
     :concepts-after (count (:concepts after))
     :relationships-after (count (:relationships after))
     :new-concept-count (count new-concept-uris)
     :sample-new-concepts (vec (take 12 (map #(select-keys % [:uri :label :attributes])
                                             (filter #(new-concept-uris (:uri %))
                                                     (:concepts after)))))}))

;; =============================================================================
;; Graph-structure stats (V09-compatible schema — feeds the V10 diff)
;; =============================================================================

(defn- uri-kind
  "Coarse concept kind by URI scheme. NOTE: V17 does NOT prescribe a scheme —
   this classifier just buckets WHATEVER schemes the builder chose, so we can
   read the graph back. The builder picks its own URI schemes."
  [uri]
  (let [u (str uri)
        i (str/index-of u ":")]
    (if i (keyword (str/lower-case (subs u 0 i))) :other)))

(defn graph-stats
  [ctx oid]
  (let [{:keys [concepts relationships]} (snapshot ctx oid)
        concept-uris (set (map :uri concepts))
        by-kind (frequencies (map #(uri-kind (:uri %)) concepts))
        by-pred (frequencies (map :predicate relationships))
        kind-of (into {} (map (juxt :uri #(uri-kind (:uri %))) concepts))
        cross-source (->> relationships
                          (filter (fn [r]
                                    (let [sk (kind-of (:source-uri r))
                                          tk (kind-of (:target-uri r))]
                                      (and sk tk (not= sk tk)))))
                          (map (fn [r] [(kind-of (:source-uri r))
                                        (:predicate r)
                                        (kind-of (:target-uri r))]))
                          frequencies)
        dangling (->> relationships
                      (remove (fn [r] (and (concept-uris (:source-uri r))
                                           (concept-uris (:target-uri r)))))
                      (mapv #(select-keys % [:source-uri :target-uri :predicate])))
        axioms (try (rm/get-axioms ctx oid) (catch Throwable _ nil))
        with-attrs (filter #(seq (:attributes %)) concepts)
        earnings-bearing (filter (fn [c]
                                   (some (fn [[k _]]
                                           (re-find #"(?i)earn|wage" (name k)))
                                         (:attributes c)))
                                 concepts)]
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
     :axiom-count (if (map? axioms)
                    (reduce + 0 (map (fn [[_ v]]
                                       (cond (sequential? v) (count v)
                                             (map? v) (count v)
                                             (set? v) (count v)
                                             :else 0))
                                     axioms))
                    0)
     :axioms axioms}))

;; =============================================================================
;; Connectivity proof — a real multi-hop path read back from the graph.
;;
;; V17 does NOT assume the builder's predicate names or URI schemes. This walk
;; is SCHEME/PREDICATE-AGNOSTIC: it just chases edges by endpoint kind to find
;; ANY program→…→occupation chain (+ whatever earnings/wage hop exists), so it
;; reads back the connectivity the builder actually produced rather than the one
;; the driver expected.
;; =============================================================================

(defn- edges-from [rels uri] (filter #(= uri (:source-uri %)) rels))
(defn- edges-to   [rels uri] (filter #(= uri (:target-uri %)) rels))

(defn- guess-kinds
  "Best-effort bucketing of the builder's chosen URI schemes into the five roles
   the goal describes, by scheme-name substring. Used ONLY to read the graph
   back (find a program, find an occupation); not fed to the builder."
  [by-kind]
  (let [kinds (set (keys by-kind))
        pick (fn [& subs]
               (some (fn [k] (when (some #(str/includes? (name k) %) subs) k)) kinds))]
    {:program     (pick "program" "prog")
     :cip         (pick "cip" "field" "discipline")
     :soc         (pick "soc" "occ" "onet")
     :institution (pick "unitid" "inst" "ipeds" "opeid")
     :earnings    (pick "pseo" "earn" "wage")}))

(defn connectivity-proof
  "Walk a real multi-hop path read back from the graph: a program concept,
   through whatever edge reaches a field/CIP concept, through whatever edge
   reaches an occupation/SOC concept (+ any earnings reachable). Scheme- and
   predicate-agnostic — reflects the builder's OWN design. Returns the first
   complete program→field→occupation chain found (with earnings if reachable),
   or a structured 'no path' explaining where the chain broke."
  [ctx oid]
  (let [{:keys [concepts relationships]} (snapshot ctx oid)
        by-uri (into {} (map (juxt :uri identity) concepts))
        kind-of (fn [u] (uri-kind u))
        roles (guess-kinds (frequencies (map #(uri-kind (:uri %)) concepts)))
        prog-kind (:program roles)
        cip-kind (:cip roles)
        soc-kind (:soc roles)
        inst-kind (:institution roles)
        earn-kind (:earnings roles)
        programs (filter #(= prog-kind (kind-of (:uri %))) concepts)
        chain
        (some
         (fn [prog]
           (let [p-uri (:uri prog)
                 cip-edge (first (filter #(= cip-kind (kind-of (:target-uri %)))
                                         (edges-from relationships p-uri)))
                 cip-uri (:target-uri cip-edge)
                 soc-edge (when cip-uri
                            (first (filter #(= soc-kind (kind-of (:target-uri %)))
                                           (edges-from relationships cip-uri))))
                 soc-uri (:target-uri soc-edge)
                 soc (get by-uri soc-uri)
                 inst-edge (first (filter #(= inst-kind (kind-of (:target-uri %)))
                                          (edges-from relationships p-uri)))
                 inst-uri (:target-uri inst-edge)
                 ;; earnings reached via the institution (either direction)
                 earn-edge (when inst-uri
                             (or (first (filter #(= earn-kind (kind-of (:source-uri %)))
                                                (edges-to relationships inst-uri)))
                                 (first (filter #(= earn-kind (kind-of (:target-uri %)))
                                                (edges-from relationships inst-uri)))))
                 earn (get by-uri (or (:source-uri earn-edge) (:target-uri earn-edge)))]
             (when (and cip-uri soc-uri)
               {:program (select-keys prog [:uri :label :attributes])
                :program->cip (select-keys cip-edge [:source-uri :predicate :target-uri])
                :cip (select-keys (get by-uri cip-uri) [:uri :label])
                :cip->soc (select-keys soc-edge [:source-uri :predicate :target-uri])
                :soc (select-keys soc [:uri :label :attributes])
                :program->institution (some-> inst-edge (select-keys [:source-uri :predicate :target-uri]))
                :institution (some-> (get by-uri inst-uri) (select-keys [:uri :label]))
                :earnings-concept (some-> earn (select-keys [:uri :label :attributes]))
                :earnings-edge (some-> earn-edge (select-keys [:source-uri :predicate :target-uri]))})))
         programs)]
    (or chain
        {:no-complete-chain true
         :roles-detected roles
         :program-count (count programs)
         :note "No program->field->occupation chain found — see graph-stats cross-source-links for where the connection broke."})))

;; =============================================================================
;; Earnings→program verdict (the MEASURED OUTCOME).
;;
;; Reads the graph back to answer: did the builder connect the earnings concepts
;; (whatever scheme it chose for them) to the program/institution side it built
;; from a DIFFERENT source's institution-id encoding? We measure institution-id
;; overlap and any earnings→{program,institution,field} edges, plus capture the
;; builder's own reasoning trace touching keys/crosswalks so the HOW is visible.
;; The driver NEVER manufactures this edge.
;; =============================================================================

(defn earnings-to-program-verdict
  [ctx oid per-source]
  (let [{:keys [concepts relationships]} (snapshot ctx oid)
        kind-of (fn [u] (uri-kind u))
        roles (guess-kinds (frequencies (map #(uri-kind (:uri %)) concepts)))
        earn-kind (:earnings roles)
        prog-kind (:program roles)
        inst-kind (:institution roles)
        cip-kind (:cip roles)
        earn-uris (set (map :uri (filter #(= earn-kind (kind-of (:uri %))) concepts)))
        ;; Any edge touching an earnings concept, and what kind it links to.
        earn-edges (->> relationships
                        (filter (fn [r] (or (earn-uris (:source-uri r))
                                            (earn-uris (:target-uri r)))))
                        (map (fn [r]
                               {:source-uri (:source-uri r)
                                :target-uri (:target-uri r)
                                :predicate (:predicate r)
                                :source-kind (kind-of (:source-uri r))
                                :target-kind (kind-of (:target-uri r))})))
        ;; Does earnings connect (transitively, 1 hop) to program/institution/field?
        earn-other-kinds (set (mapcat (fn [e] [(:source-kind e) (:target-kind e)]) earn-edges))
        connects-to (set/intersection earn-other-kinds
                                      #{prog-kind inst-kind cip-kind})
        ;; institution-id overlap: do earnings-side institution refs share values
        ;; with program-side institution refs? (the V09 key-encoding mismatch)
        inst-targets-from-earnings (set (keep (fn [e]
                                                (when (= inst-kind (:target-kind e)) (:target-uri e)))
                                              earn-edges))
        inst-from-programs (set (keep (fn [r]
                                        (when (and (= prog-kind (kind-of (:source-uri r)))
                                                   (= inst-kind (kind-of (:target-uri r))))
                                          (:target-uri r)))
                                      relationships))
        inst-overlap (set/intersection inst-targets-from-earnings inst-from-programs)
        ;; Pull any reasoning lines mentioning keys/crosswalks/ids from the
        ;; earnings (pseo / wages) sources verbatim.
        key-reasoning (->> per-source
                           (filter #(#{:pseo :wages :ipeds} (:source %)))
                           (mapcat (fn [s]
                                     (map (fn [line] {:source (:source s) :line line})
                                          (concat (:iteration-reasonings s) (:rlm-trace s)))))
                           (filter (fn [{:keys [line]}]
                                     (re-find #"(?i)key|cross|opeid|unitid|ipeds|institution|merge|join|bridge|link|crosswalk"
                                              (str line))))
                           vec)]
    {:earnings-concept-count (count earn-uris)
     :earnings-edge-count (count earn-edges)
     :earnings-edges-by-link (frequencies (map (juxt :source-kind :predicate :target-kind) earn-edges))
     :earnings-connects-to-program-side? (boolean (seq connects-to))
     :connects-to-kinds connects-to
     :institution-id-overlap-count (count inst-overlap)
     :sample-institution-overlap (vec (take 6 inst-overlap))
     :bridge-discovered?
     ;; The bridge is "discovered" iff earnings reach the program side either by
     ;; a direct earnings→program/field edge OR by sharing institution ids with
     ;; the program side (so the institution hop completes).
     (boolean (or (seq (set/intersection connects-to #{prog-kind cip-kind}))
                  (seq inst-overlap)))
     :builder-key-reasoning key-reasoning}))

;; =============================================================================
;; Retrievability probes (labeled hybrid-search hits)
;; =============================================================================

(defn retrievability-probes! [ctx oid]
  (into {}
        (for [q ["psychology bachelor's degree"
                 "social work program"
                 "registered nurse occupation"
                 "computer science engineering"
                 "clinical psychologist earnings"]]
          [q (->> (ontology/hybrid-search ctx {:query-text q
                                               :ontology-ids [oid]
                                               :limit 5})
                  :results
                  (mapv (fn [r] {:uri (:uri r) :label (:label r) :score (:score r)})))])))

;; =============================================================================
;; Orchestrator
;; =============================================================================

(defn run!
  "Run the full V17 autonomous graph-B build. Required env: OPENROUTER_API_KEY.
   Options:
     :model   — OpenRouter model (default gemini-3-flash-preview).
     :budget  — discovery budget per source. GENEROUS by default — autonomous
                discovery + comprehensive paging over a source needs many
                iterations.
     :only    — vector of source :name keys to run a subset (debug)."
  [{:keys [model budget only]
    :or {model default-model
         ;; Generous: autonomous exploration + comprehensive offset-paging over
         ;; a source needs many iterations. :max-retries reuses the executor
         ;; primitive for cold-start blank completions.
         budget {:max-iterations 24 :total-budget-ms 1800000 :max-retries 3}}}]
  (let [ctx (make-ctx)
        oid (random-uuid)
        srcs (cond->> (sources) only (filter #(some #{(:name %)} only)))]
    (try
      (register-openrouter! model)
      (println "=== V17 GRAPH B FULL-SCALE BUILD (autonomous discovery) ===")
      (println "Ontology-id:" oid "  model:" model)
      (println "Sources:" (mapv :name srcs))
      (println "Budget:" budget)
      (let [per-source
            (mapv (fn [src]
                    (println "\n>>> discovering source:" (:name src)
                             "(" (name (:type src)) ")  path:" (:path src))
                    (let [t0 (System/currentTimeMillis)
                          r (discover-source! ctx oid src model budget)
                          dt (- (System/currentTimeMillis) t0)]
                      (println "    status:" (:discovery-status r)
                               " emitted c/r/a:"
                               (:emitted-concepts r) "/"
                               (:emitted-relationships r) "/"
                               (:emitted-axioms r)
                               " graph now:" (:concepts-after r) "concepts /"
                               (:relationships-after r) "rels"
                               " (" dt "ms)")
                      (when (:session-error r)
                        (println "    SESSION ERROR:" (:session-error r)))
                      (when (:compile-error r)
                        (println "    COMPILE FAILED (source contributes 0):" (:compile-error r))
                        (println "    offending draft:" (pr-str (:offending-draft r))))
                      (assoc r :discovery-ms dt)))
                  srcs)
            _ (println "\n>>> running deterministic skeleton over the accumulated graph")
            t-sk (System/currentTimeMillis)
            skeleton ((requiring-resolve
                       'ai.obney.orc.ontology.core.deterministic-skeleton/build!)
                      ctx {:ontology-id oid
                           :sources [{:type :inline-concepts :concepts []}]
                           :validation {:halt-on :none}})
            sk-ms (- (System/currentTimeMillis) t-sk)
            _ (println "    skeleton status:" (:status skeleton)
                       " stages:" (:stages-run skeleton) " (" sk-ms "ms)")
            _ (println "\n>>> capturing graph-structure stats")
            stats (graph-stats ctx oid)
            _ (println "    concepts:" (:concept-count stats)
                       " rels:" (:relationship-count stats)
                       " cross-source links:" (:cross-source-link-total stats)
                       " earnings/wage concepts:" (:earnings-or-wage-bearing-concepts stats))
            _ (println "\n>>> connectivity proof (multi-hop path read-back)")
            conn (connectivity-proof ctx oid)
            _ (println "    " (if (:no-complete-chain conn) "NO COMPLETE CHAIN" "CHAIN FOUND"))
            _ (println "\n>>> earnings->program verdict (MEASURED OUTCOME)")
            earn-verdict (earnings-to-program-verdict ctx oid per-source)
            _ (println "    bridge-discovered?:" (:bridge-discovered? earn-verdict)
                       " inst-id-overlap:" (:institution-id-overlap-count earn-verdict)
                       " earnings-edges:" (:earnings-edge-count earn-verdict))
            _ (println "\n>>> retrievability probes (labeled hybrid-search)")
            probes (retrievability-probes! ctx oid)]
        (println "\n=== DONE ===")
        {:ontology-id oid
         :model model
         :budget budget
         :sources (mapv :name srcs)
         :per-source per-source
         :skeleton skeleton
         :skeleton-ms sk-ms
         :stats stats
         :connectivity conn
         :earnings-verdict earn-verdict
         :probes probes
         ::concepts (:concepts (snapshot ctx oid))
         ::relationships (:relationships (snapshot ctx oid))})
      (finally (stop-ctx ctx)))))

;; =============================================================================
;; Capture + artifact
;; =============================================================================

(defn save-artifact! [result]
  (io/make-parents artifact-path)
  (spit artifact-path
        (pr-str {:ontology-id (:ontology-id result)
                 :model (:model result)
                 :concepts (mapv #(select-keys % [:uri :label :description :scope
                                                  :indicators :attributes :broader])
                                 (::concepts result))
                 :relationships (mapv #(select-keys % [:source-uri :target-uri
                                                       :predicate :confidence-class])
                                      (::relationships result))
                 :stats (dissoc (:stats result) :axioms)
                 :axioms (get-in result [:stats :axioms])}))
  artifact-path)

(defn load-artifact [] (edn/read-string (slurp artifact-path)))

(defn print-summary! [r]
  (println "\n================ V17 GRAPH B SUMMARY ================")
  (println "ontology-id:" (:ontology-id r) " model:" (:model r))
  (println "\n--- per-source ---")
  (doseq [s (:per-source r)]
    (println (format "  %-10s status=%s emitted c/r/a=%d/%d/%d (%dms)"
                     (name (:source s)) (:discovery-status s)
                     (:emitted-concepts s) (:emitted-relationships s) (:emitted-axioms s)
                     (or (:discovery-ms s) 0))))
  (println "\n--- skeleton ---")
  (println "  status:" (get-in r [:skeleton :status])
           " stages:" (get-in r [:skeleton :stages-run]))
  (println "\n--- graph stats ---")
  (pp/pprint (dissoc (:stats r) :axioms))
  (println "\n--- connectivity proof ---")
  (pp/pprint (:connectivity r))
  (println "\n--- earnings->program verdict ---")
  (pp/pprint (:earnings-verdict r))
  (println "\n--- retrievability ---")
  (pp/pprint (:probes r)))

(defn save-capture!
  "Write the live-verify capture doc (verbatim prompts/stats/proof/verdict) +
   the artifact. The EXACT prompts handed to the builder are captured so the
   no-hardcoding rule is auditable."
  [r]
  (save-artifact! r)
  (io/make-parents capture-path)
  (let [sk (let [sk0 (dissoc (:skeleton r) :artifacts)
                 drr (:dedup-review-required sk0)]
             (cond-> sk0
               (seq drr)
               (assoc :dedup-review-required
                      {:count (count drr)
                       :sample (first drr)
                       :note "elided — repeated budget-exhausted entries (dedup ran with :llm-budget 0)"})))]
    (spit capture-path
          (str "# V17 — Graph B full-scale rebuild (AUTONOMOUS discovery) — LIVE VERIFY\n\n"
               "**Date:** 2026-06-16. **Branch:** `feature/ontology-architecture`.\n"
               "**Model:** `" (:model r) "` (real OpenRouter). **Embeddings:** local "
               "all-MiniLM-L6-v2 (DJL, 384-dim). **ColBERT:** real index. **No mocks.**\n\n"
               "Ontology-id: `" (:ontology-id r) "`. Sources (path + format ONLY — "
               "contents are the builder's to discover): " (pr-str (:sources r)) ".\n\n"
               "Budget per source: `" (pr-str (:budget r)) "`.\n\n"
               "Artifact (loadable by V10/V12): `" artifact-path "`.\n\n"

               "## The no-hardcoding audit — EXACT prompts handed to the builder\n\n"
               "The driver's entire per-source 'hand' is the DOMAIN GOAL (identical for "
               "every source) prepended to the shipped `default-discovery-prompt`. NO "
               "table names, column names/indices, row offsets, LIMIT values, join keys, "
               "or crosswalk recipes appear below. (`run-discovery!` further prepends the "
               "shipped per-format exploration MECHANICS for a structured source — those "
               "name no domain key; they describe how to call the sampling tools.)\n\n"
               "### The DOMAIN GOAL (verbatim — the only per-source driver text)\n\n```\n"
               domain-goal
               "```\n\n### The full assembled prompt per source (verbatim)\n\n"
               (str/join "\n"
                 (map (fn [s]
                        (str "#### Source `" (name (:source s)) "`\n\n```\n"
                             (:prompt-handed s)
                             "\n```\n"))
                      (:per-source r)))
               "\n## Per-source ingestion outcome — including the builder's discovery "
               "trace (verbatim)\n\n```clojure\n"
               (with-out-str (pp/pprint (mapv #(dissoc % :usage :prompt-handed) (:per-source r))))
               "```\n\n## Skeleton terminal status\n\n```clojure\n"
               (with-out-str (pp/pprint sk))
               "```\n\nSkeleton wall-clock: " (:skeleton-ms r) " ms.\n\n"
               "## Graph-structure stats (V09 schema — feeds V10 diff)\n\n```clojure\n"
               (with-out-str (pp/pprint (:stats r)))
               "```\n\n## Connectivity proof (multi-hop path read back from the graph)\n\n```clojure\n"
               (with-out-str (pp/pprint (:connectivity r)))
               "```\n\n## Earnings→program verdict (the MEASURED OUTCOME)\n\n"
               "Did the builder discover the bridge between earnings (one source's "
               "institution-id encoding) and programs (another's) ON ITS OWN? Measured "
               "from the read-back graph + the builder's own reasoning trace. The driver "
               "named no key and added no driver-side join.\n\n```clojure\n"
               (with-out-str (pp/pprint (:earnings-verdict r)))
               "```\n\n## Retrievability — labeled hybrid-search hits\n\n```clojure\n"
               (with-out-str (pp/pprint (:probes r)))
               "```\n")))
  (println "Capture written:" capture-path)
  (println "Artifact written:" artifact-path)
  capture-path)

(comment
  (require '[v17-graph-b-full-scale :as v17] :reload)
  (def r (v17/run! {}))
  (v17/print-summary! r)
  (v17/save-capture! r))
