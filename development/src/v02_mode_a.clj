(ns v02-mode-a
  "V02 — Mode A early read (brownfield BRYC graph).

   Ingests the EXISTING production BRYC graph (louisiana_programs_full.ttl)
   into the NEW substrate, auto-embeds/indexes it, and runs a per-vertical
   RLM exploration read for the Trinity profile — the early signal on
   substrate + retrieval + exploration quality with ZERO new ingestion work
   (the brownfield 'bring your own graph, we improve + extend it' proof).

   USAGE (REPL with :dev alias, OPENROUTER_API_KEY set in env ONLY):

     (require '[v02-mode-a :as v])
     (v/run! {:model \"gemini-3-flash-preview\"})

   The run, in order:
   1. SCALE + ROUND-TRIP CHECK — ingest the full 45 MB TTL via the S09
      `ingest-ttl!` path; report node/edge counts + time. (This is the
      faithful test of the shipped brownfield ingest.)
   2. DRIVER-SIDE ADAPTER — because S09 only recognizes `skos:Concept`
      subjects and the production TTL is typed with DOMAIN classes
      (edu:EducationalProgram / cip:CIPCode / onet:Occupation under
      example.org namespaces NOT in the S09 prefix table), step 1 yields
      0 concepts. To still exercise the substrate/retrieval/exploration on
      the REAL graph data, this driver maps the TTL individuals into the
      substrate via the PUBLIC create-concept / create-relationship
      commands (the same seam `:inline-concepts` uses). This is the
      brownfield gap made explicit — see the live-verify doc.
   3. AUTO-EMBED + COLBERT INDEX — real local all-MiniLM-L6-v2 embeddings
      via the public embed-concept command + a real ColBERT index.
   4. RETRIEVABILITY — hybrid-search probes confirm concepts are findable.
   5. PER-VERTICAL RLM EXPLORATION — career / financial / outcome /
      academic / preference, for the Trinity profile, over the S19 graph
      tools + recursive-RLM, against a REAL LLM. Verbatim capture.

   Outputs are written to docs/build-timeline/live-verify/V02-mode-a-early-read.md."
  (:require [ai.obney.orc.ontology.interface.schemas]
            [ai.obney.orc.ontology.core.commands :as cmd]
            [ai.obney.orc.ontology.core.read-models :as rm]
            [ai.obney.orc.ontology.core.ttl-ingest :as ttl-ingest]
            [ai.obney.orc.ontology.core.colbert-indexer :as colbert-indexer]
            [ai.obney.orc.ontology.interface :as ontology]
            [ai.obney.orc.orc-service.core.rlm-sandbox :as rlm-sandbox]
            [ai.obney.orc.orc-service.core.sandbox-tools :as st]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.query-processor.interface :as qp]
            [ai.obney.grain.pubsub.interface :as pubsub]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp]
            [ai.obney.orc.orc-service.core.todo-processors]
            [ai.obney.grain.todo-processor-v2.interface :as tp]
            [ai.obney.grain.time.interface :as time]
            [ai.obney.orc.ontology.core.ttl-canonicalize]
            [litellm.router :as litellm-router]
            [clojure.string :as str]
            [clojure.pprint :as pp]
            [dscloj.core :as dscloj]))

(def ttl-path
  "/Users/darylroberts/Desktop/Code/area_51/ontology_exploration/output/louisiana_programs_full.ttl")

;; =============================================================================
;; Provider + context wiring (mirrors s18-live-verify)
;; =============================================================================

(defn- register-openrouter! [model]
  (let [api-key (or (System/getenv "OPENROUTER_API_KEY")
                    (throw (ex-info "OPENROUTER_API_KEY not set (env var only)" {})))
        base-config {:provider :openrouter
                     :model model
                     :config {:api-base "https://openrouter.ai/api/v1"
                              :api-key api-key}}]
    (litellm-router/register! :openrouter base-config)
    (litellm-router/register! (keyword (str "openrouter/" model)) base-config)))

(defn- make-ctx []
  (rmp/l1-clear!)
  (let [ps (pubsub/start {:type :core-async :topic-fn :event/type})
        store (es/start {:conn {:type :in-memory} :event-pubsub ps :logger nil})
        dir (str "/tmp/v02-mode-a-" (random-uuid))
        ;; Default LMDB map-size is 10 MB — far too small for a real-sized
        ;; graph (2.5k concepts × 384-dim embeddings). Bump to 4 GB so the
        ;; embeddings projection fits. (Real finding — see the doc.)
        cache (kv/start (lmdb/->KV-Store-LMDB
                         {:storage-dir dir :db-name "test"
                          :map-size (* 4 1024 1024 1024)}))
        base-ctx {:event-store store
                  :cache cache
                  :tenant-id (random-uuid)
                  :dscloj-provider :openrouter
                  :command-registry (cp/global-command-registry)
                  :query-registry (qp/global-query-registry)
                  :event-pubsub ps
                  ::cache-dir dir}
        processors (reduce-kv
                    (fn [acc proc-name {:keys [handler-fn topics]}]
                      (assoc acc proc-name
                             (tp/start {:event-pubsub ps
                                        :topics topics
                                        :handler-fn handler-fn
                                        :context base-ctx})))
                    {}
                    @tp/processor-registry*)]
    (assoc base-ctx :processors processors)))

(defn- stop-ctx [ctx]
  (doseq [[_ processor] (:processors ctx)] (tp/stop processor))
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
;; STEP 1 — shipped S09 ingest scale + round-trip check
;; =============================================================================

(defn scale-check!
  "Run the SHIPPED S09 ingest-ttl! over the full production TTL and report
   counts + timing. This is the honest test of the brownfield ingest path."
  [ctx ttl ontology-id]
  (let [t0 (System/currentTimeMillis)
        report (ttl-ingest/ingest-ttl! ctx ttl {:ontology-id ontology-id})
        t1 (System/currentTimeMillis)]
    (Thread/sleep 400)
    (let [concepts (filter #(= ontology-id (:ontology-id %)) (rm/get-concepts ctx {}))
          rels (filter #(= ontology-id (:ontology-id %)) (rm/get-relationships ctx))]
      {:ingest-ms (- t1 t0)
       :ingested? (:ingested? report)
       :triples-parsed (:triples-parsed report)
       :counts (:counts report)
       :anomaly (:anomaly/message report)
       :projected-concepts (count concepts)
       :projected-relationships (count rels)})))

;; =============================================================================
;; STEP 2 — driver-side TTL → substrate adapter (brownfield gap workaround)
;; =============================================================================
;;
;; S09 recognizes only `skos:Concept` subjects; the production TTL uses
;; domain types under example.org namespaces. To exercise the substrate +
;; retrieval + exploration on the REAL graph data we parse the canonical
;; N-Triples (rdflib already proved them parseable in step 1) and route the
;; domain individuals through the PUBLIC create-concept / create-relationship
;; commands. NOTE — this is DRIVER-side, NOT a change to the ingest path.

(def ^:private object-edge-predicates
  "rdflib-canonical IRIs for the domain edges that connect the graph.
   These are the cross-source connective tissue: program→CIP, CIP→SOC."
  {"http://example.org/education#hasCIPCodeEntity" "edu:hasCIPCodeEntity"
   "http://example.org/cip#leadsToOccupation"      "cip:leadsToOccupation"
   "http://example.org/education#hasSector"        "edu:hasSector"
   "http://example.org/education#hasAwardLevel"     "edu:hasAwardLevel"})

(def ^:private concept-types
  {"http://example.org/education#EducationalProgram" :program
   "http://example.org/cip#CIPCode"                  :cip
   "http://example.org/onet#Occupation"              :occupation
   "http://example.org/education#Discipline"         :discipline
   "http://example.org/education#Sector"             :sector
   "http://example.org/education#Awardlevel"         :awardlevel})

(defn- iri->short
  "Compress a full example.org IRI into a prefixed short form used as URI."
  [iri]
  (-> iri
      (str/replace "http://example.org/education#" "edu:")
      (str/replace "http://example.org/cip#" "cip:")
      (str/replace "http://example.org/soc#" "soc:")
      (str/replace "http://example.org/onet#" "onet:")
      (str/replace "http://example.org/labor#" "labor:")))

(def ^:private literal-attr-keys
  "Literal predicates worth carrying onto a concept so retrieval has
   semantic + grounding signal (label/description + key facts). Embeddings
   in the source are DROPPED (we re-embed locally)."
  {"http://example.org/education#enrichedDescription" :description
   "http://example.org/education#institutionName"     :institution
   "http://example.org/education#city"                :city
   "http://example.org/education#awardCategory"       :award-category
   "http://example.org/education#earningsY1Estimated" :earnings-y1
   "http://example.org/education#earningsY5Estimated" :earnings-y5
   "http://example.org/education#averageIncomeAnnual" :avg-income
   "http://example.org/education#inStateTuition"      :in-state-tuition
   "http://example.org/education#historicallyBlackCollegeUniversity" :hbcu
   "http://example.org/education#cipCode"             :cip-code
   "http://example.org/education#awardLevelName"      :award-level-name
   "http://example.org/cip#cipCodeValue"              :cip-code-value
   "http://www.w3.org/2000/01/rdf-schema#label"       :label})

(defn- strip< [s] (if (and (str/starts-with? s "<") (str/ends-with? s ">"))
                    (subs s 1 (dec (count s))) s))

(defn- lit-val [o]
  ;; canonical n3 literal: "..."^^<dt> | "..."@lang | "..."
  (when (str/starts-with? o "\"")
    (let [end (str/last-index-of o "\"")]
      (when (pos? end)
        (-> (subs o 1 end)
            (str/replace "\\\"" "\"")
            (str/replace "\\n" " "))))))

(defn adapt-ttl->graph!
  "Parse canonical N-Triples and ingest domain individuals as concepts +
   the object-property edges as relationships via PUBLIC commands. Returns
   counts. Programs/CIPs/Occupations only (the explorable graph)."
  [ctx ontology-id ntriples]
  (let [lines (->> (str/split-lines ntriples) (remove str/blank?))
        ;; parse [s p o] — s/p bracketed IRIs, o IRI|literal
        triples (keep (fn [line]
                        (let [l (str/trim line)
                              l (if (str/ends-with? l " .") (subs l 0 (- (count l) 2)) l)
                              sp1 (.indexOf l (int \space))
                              s (subs l 0 sp1)
                              rest1 (subs l (inc sp1))
                              sp2 (.indexOf rest1 (int \space))
                              p (subs rest1 0 sp2)
                              o (subs rest1 (inc sp2))]
                          (when (and (pos? sp1) (pos? sp2))
                            [(strip< s) (strip< p) o])))
                      lines)
        by-subj (group-by first triples)
        ;; classify subjects by rdf:type
        type-iri "http://www.w3.org/1999/02/22-rdf-syntax-ns#type"
        subj-kind (into {}
                        (keep (fn [[s pos]]
                                (let [types (->> pos
                                                 (filter #(= type-iri (second %)))
                                                 (map #(strip< (nth % 2)))
                                                 set)
                                      kind (some concept-types types)]
                                  (when kind [s kind]))))
                        by-subj)
        ;; emit concepts
        concept-uris (atom #{})
        c-count (atom 0)]
    (doseq [[s pos] by-subj
            :let [kind (subj-kind s)]
            :when kind]
      (let [attrs (reduce (fn [acc [_ p o]]
                            (if-let [k (literal-attr-keys p)]
                              (let [v (lit-val o)] (cond-> acc v (assoc k v)))
                              acc))
                          {} pos)
            uri (iri->short s)
            label (or (:label attrs) (:institution attrs) uri)
            desc (or (:description attrs)
                     (str (name kind)
                          (when (:institution attrs) (str " — " (:institution attrs)))
                          (when (:city attrs) (str ", " (:city attrs)))
                          (when (:award-category attrs) (str " (" (:award-category attrs) ")"))))]
        (cp/process-command
         (assoc ctx :command
                {:command/name :ontology/create-concept
                 :command/id (random-uuid)
                 :command/timestamp (time/now)
                 :ontology-id ontology-id
                 :uri uri
                 :label (subs label 0 (min 200 (count label)))
                 :description (subs desc 0 (min 1200 (count desc)))
                 :scope :custom
                 :broader []
                 :indicators (vec (keep identity
                                        [(:cip-code-value attrs) (:cip-code attrs)
                                         (:award-level-name attrs) (:city attrs)]))}))
        (swap! concept-uris conj uri)
        (swap! c-count inc)))
    ;; emit object-property edges (only when both endpoints are concepts)
    (let [r-count (atom 0)]
      (doseq [[s pos] by-subj
              :when (subj-kind s)
              [_ p o] pos
              :let [pred (object-edge-predicates p)]
              :when (and pred (str/starts-with? o "<"))]
        (let [tgt (iri->short (strip< o))
              src (iri->short s)]
          (when (and (@concept-uris src) (@concept-uris tgt))
            (cp/process-command
             (assoc ctx :command
                    {:command/name :ontology/create-relationship
                     :command/id (random-uuid)
                     :command/timestamp (time/now)
                     :ontology-id ontology-id
                     :source-uri src
                     :target-uri tgt
                     :predicate pred
                     :confidence-class :extracted
                     :properties {}}))
            (swap! r-count inc))))
      {:concepts @c-count :relationships @r-count
       :concept-kinds (frequencies (vals subj-kind))})))

;; =============================================================================
;; STEP 3 — auto-embed + ColBERT index (real local embeddings)
;; =============================================================================

(defn embed-and-index!
  "Embed every concept (real all-MiniLM-L6-v2) via the public embed-concept
   command, then build a real ColBERT index. Returns counts."
  [ctx ontology-id]
  (let [concepts (filterv #(= ontology-id (:ontology-id %)) (rm/get-concepts ctx {}))
        t0 (System/currentTimeMillis)
        embedded (reduce
                  (fn [acc c]
                    (let [r (cp/process-command
                             (assoc ctx :command
                                    {:command/name :ontology/embed-concept
                                     :command/id (random-uuid)
                                     :command/timestamp (time/now)
                                     :uri (:uri c)
                                     :fields #{:label :description :indicators}}))]
                      (if (pos? (or (get-in r [:command-result/data :dimensions]) 0))
                        (inc acc) acc)))
                  0 concepts)
        t1 (System/currentTimeMillis)
        idx (try
              (colbert-indexer/index-concepts! ctx concepts
                                               {:auto-detect-colbert-fields true})
              (catch Exception e {:error (.getMessage e)}))
        t2 (System/currentTimeMillis)]
    (when (and (pos? (or (:document-count idx) 0)) (uuid? (:index-id idx)))
      (colbert-indexer/emit-colbert-indexed-event!
       ctx {:ontology-id ontology-id
            :index-id (:index-id idx)
            :index-name (:index-name idx)
            :document-count (:document-count idx)
            :colbert-fields (:colbert-fields idx)}))
    {:concepts (count concepts)
     :embedded embedded
     :embed-ms (- t1 t0)
     :index-ms (- t2 t1)
     :index (select-keys idx [:index-id :index-name :document-count :colbert-fields :error])}))

;; =============================================================================
;; STEP 4 — retrievability probes
;; =============================================================================

(defn retrievability-probes! [ctx ontology-id]
  (into {}
        (for [q ["psychology bachelor's degree"
                 "social work program"
                 "computer science engineering"
                 "early childhood education apprenticeship"]]
          [q (->> (ontology/hybrid-search ctx {:query-text q
                                               :ontology-ids [ontology-id]
                                               :limit 5})
                  :results
                  (mapv (fn [r] {:uri (:uri r) :label (:label r) :score (:score r)})))])))

;; =============================================================================
;; STEP 5 — per-vertical RLM exploration (Trinity)
;; =============================================================================

(def trinity-profile
  (str "STUDENT PROFILE — Trinity:\n"
       "  GPA 3.0, ACT 24, color-profile super-purple (4-year focus),\n"
       "  TOPS award: opportunity (100% tuition coverage),\n"
       "  Career fields: Law, Psychology, Social Work,\n"
       "  Interests: Southern University, LSU, Xavier, Tulane, Spelman, Howard "
       "(HBCU preference)."))

(def vertical-tasks
  {:career
   "CAREER vertical: Find EducationalPrograms whose CIP codes map (via the graph's program→CIP and CIP→occupation edges) to Trinity's career fields (Law, Psychology, Social Work). Surface the specific programs + the connection path you followed."
   :financial
   "FINANCIAL vertical: Among the career-relevant programs, which are most affordable given Trinity's TOPS-opportunity award (100% tuition)? Use in-state-tuition / award-category signals on the program concepts."
   :outcome
   "OUTCOME vertical: Among the career-relevant programs, which have the strongest earnings outcomes (earnings-y1 / earnings-y5 vs a ~$52,500 LA median)? Cite the values you find on the concepts."
   :academic
   "ACADEMIC vertical: Trinity is super-purple (4-year / bachelor's focus). Which of the relevant programs are bachelor's-level (award-level-name) and a good academic fit? Exclude apprenticeship/certificate-only programs."
   :preference
   "PREFERENCE vertical: Trinity prefers HBCUs (Southern, Xavier, Grambling) and named institutions. Which relevant programs are at HBCUs or her named institutions? Use the hbcu / institution signals."})

(def tool-affordances-block
  (str
   "TOOLS — each with PURPOSE / EXAMPLE / RETURNS. Call them like Clojure fns:\n\n"
   (->> ['graph-search 'neighborhood 'get-concept 'exists? 'absent-in-graph?
         'find-edges 'filter-by-label-pattern]
        (map (fn [sym] (str "### " sym "\n" (get st/ontology-tool-docs sym) "\n")))
        (str/join "\n"))))

(defn- explore-vertical!
  "Run a single recursive-RLM vertical exploration over the granted graph.
   Returns the full transcript + final output VERBATIM."
  [ctx ontology-id model vertical task max-iterations]
  (let [rlm-ctx (rlm-sandbox/build-rlm-context
                 {:provider :openrouter
                  :blackboard {}
                  :declared-writes [:recommendations :connections :reasoning]
                  :recursive? true
                  :event-store (:event-store ctx)
                  :tenant-id (:tenant-id ctx)
                  :cache (:cache ctx)
                  :granted-ontology-id ontology-id})
        base-prompt
        (str "You are a recursive RLM researcher exploring a Louisiana "
             "career/program ontology graph for a student recommendation.\n\n"
             trinity-profile "\n\n"
             tool-affordances-block "\n\n"
             "Concept URIs look like edu:<program>, cip:<code>, soc:<occupation>. "
             "Programs carry description + indicators (cip-code, city, award level). "
             "Edges: edu:hasCIPCodeEntity (program→CIP), cip:leadsToOccupation (CIP→occupation).\n\n"
             "YOUR TASK — " (name vertical) " vertical:\n" task "\n\n"
             "Use graph-search to find seed concepts, then neighborhood / find-edges "
             "to follow connections, and get-concept to read the grounding facts. "
             "End with (final! {:recommendations [...] :connections [...] :reasoning \"...\"}) "
             "where :recommendations is a vector of concept-uri strings with a one-line why each.")
        transcript (atom [])]
    (loop [iter 0 history []]
      (if (>= iter max-iterations)
        {:vertical vertical :status :exhausted-iterations
         :transcript @transcript :final-output @(:final-output rlm-ctx)}
        (let [prompt (str base-prompt
                          "\n\n=== TRANSCRIPT SO FAR ===\n"
                          (str/join "\n---\n" history)
                          "\n\nWrite your NEXT single Clojure form (no prose):")
              module {:inputs [{:name :prompt :spec :any :description "Task"}]
                      :outputs [{:name :code :spec :any :description "next form"}]
                      :instructions prompt}
              code-resp (dscloj/predict (keyword "openrouter" model)
                                        module {:prompt prompt}
                                        {:validate? false :with-metadata? true})
              code (or (:code (:outputs code-resp)) (:code code-resp))
              exec (rlm-sandbox/execute-rlm-code rlm-ctx code)
              block (str "ITER " iter "\nCODE:\n" code
                         "\nRESULT: " (pr-str (:result exec))
                         (when (:error exec) (str "\nERROR: " (:error exec))))]
          (swap! transcript conj {:iter iter :code code
                                  :result (:result exec) :error (:error exec)
                                  :final-output (:final-output exec)})
          (if (:final-output exec)
            {:vertical vertical :status :final
             :transcript @transcript :final-output (:final-output exec)
             :iterations (inc iter)}
            (recur (inc iter) (conj history block))))))))

(defn explore-all-verticals! [ctx ontology-id model max-iterations]
  (mapv (fn [[v task]]
          (println "  >>> vertical:" v)
          (explore-vertical! ctx ontology-id model v task max-iterations))
        vertical-tasks))

;; =============================================================================
;; Orchestrator
;; =============================================================================

(defn run!
  "Run the full V02 Mode A early read. Returns the full result map for
   doc capture. Required env: OPENROUTER_API_KEY."
  [{:keys [model max-iterations]
    :or {model "google/gemini-3-flash-preview" max-iterations 8}}]
  (let [ctx (make-ctx)
        oid (random-uuid)]
    (try
      (register-openrouter! model)
      (println "=== V02 MODE A EARLY READ ===")
      (println "Ontology-id:" oid)
      (let [ttl (slurp ttl-path)
            _ (println "TTL bytes:" (count ttl))
            ;; STEP 1 — shipped S09 ingest
            _ (println "\n>>> STEP 1 — shipped S09 ingest-ttl! scale/round-trip check")
            scale (scale-check! ctx ttl oid)
            _ (pp/pprint scale)
            ;; STEP 2 — driver-side adapter (using canonical n-triples)
            _ (println "\n>>> STEP 2 — driver-side TTL→substrate adapter")
            ntriples (ai.obney.orc.ontology.core.ttl-canonicalize/canonicalize-ttl ttl)
            adapt-oid (random-uuid)
            adapt (if (string? ntriples)
                    (adapt-ttl->graph! ctx adapt-oid ntriples)
                    {:error (str "canonicalize failed: " (:anomaly/message ntriples))})
            _ (println "adapter:" (pr-str (dissoc adapt :concept-kinds)))
            _ (println "concept-kinds:" (pr-str (:concept-kinds adapt)))
            _ (Thread/sleep 500)
            ;; STEP 3 — embed + index
            _ (println "\n>>> STEP 3 — embed + ColBERT index (real local embeddings)")
            embed (embed-and-index! ctx adapt-oid)
            _ (pp/pprint embed)
            _ (Thread/sleep 500)
            ;; STEP 4 — retrievability
            _ (println "\n>>> STEP 4 — retrievability probes")
            probes (retrievability-probes! ctx adapt-oid)
            _ (pp/pprint probes)
            ;; STEP 5 — per-vertical RLM exploration
            _ (println "\n>>> STEP 5 — per-vertical RLM exploration (Trinity)")
            verticals (explore-all-verticals! ctx adapt-oid model max-iterations)]
        (println "\n=== DONE ===")
        {:ontology-id oid
         :adapt-ontology-id adapt-oid
         :scale scale
         :adapt adapt
         :embed embed
         :probes probes
         :verticals verticals
         :model model})
      (finally (stop-ctx ctx)))))

(defn print-verticals! [result]
  (doseq [{:keys [vertical status iterations final-output transcript]} (:verticals result)]
    (println "\n================ VERTICAL:" vertical "================")
    (println "status:" status " iterations:" iterations)
    (println "FINAL OUTPUT:")
    (pp/pprint final-output)
    (println "--- transcript ---")
    (doseq [s transcript]
      (println "ITER" (:iter s))
      (println "CODE:" (:code s))
      (println "RESULT:" (pr-str (:result s)))
      (when (:error s) (println "ERROR:" (:error s))))))
