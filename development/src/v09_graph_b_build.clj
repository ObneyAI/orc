(ns v09-graph-b-build
  "V09 — Graph B build (the NEW builder) over the 5 real official sources.

   This is the largest live run of the ontology-verification initiative: the
   new system's format-aware, RLM-controlled ingestion (V06) + auto-embed (V01)
   + axiom ingest (V07), through the deterministic skeleton (S17), over the same
   5 official sources A1 uses. The output graph B is the new side of the
   head-to-head and is SAVED so V10 (diff) and V12 (exploration) can load it.

   THE 5 SOURCES (each builder assembles its own program set + its OWN
   embeddings — the hand-made embeddings CSV is EXCLUDED):
     1. IPEDS      — SQLite output.db (programs via UNITID+CIPCODE, CIP codes,
                     institutions). 59 tables, ZERO declared FKs — joins by
                     shared UNITID / CIPCODE keys.
     2. crosswalk  — CSV cip_soc_crosswalk.csv (CIP_Code -> SOC_Code — THE bridge
                     that connects the program side to the occupation side).
     3. O*NET      — Excel dir (Occupation Data — SOC occupations).
     4. LA-OEWS    — CSV louisiana_occupation_wages.csv (median_wage per SOC).
     5. PSEO       — Excel pseo_la.xlsx (Census earnings y1/y5/y10 by
                     institution + cipcode + degree).

   ARCHITECTURE. A single discovery session explores ONE source's format (V06's
   proven one-source-per-session pattern — `run-discovery!` grants the FIRST
   structured source's format tools). So the driver runs discovery PER SOURCE
   against the SAME ontology-id, compiling each session's drafts into events on
   that shared graph, THEN runs the deterministic skeleton ONCE over the
   accumulated graph (parse no-op stub -> normalize -> dedup -> validate ->
   embed -> index -> CQ exit -> terminal status).

   CROSS-SOURCE CONNECTIVITY is the whole point: every source mints SHAREABLE
   canonical URIs (cip:<code>, soc:<code>, unitid:<id>) so a cip: from IPEDS and
   a cip: from the crosswalk resolve to the SAME concept and the crosswalk's
   cip->soc edges connect the two sides. The driver proves this with a real
   multi-hop program->CIP->SOC->earnings path read back from the graph.

   EARNINGS / WAGES are acceptance-critical (V02's outcome vertical failed
   because earnings were dropped). The discovery prompt now instructs the model
   to carry numeric outcomes in :attributes, and `concept-draft->command`
   forwards :attributes onto the concept — so they land as QUERYABLE concept
   attributes. The driver verifies by reading a program/occupation concept back.

   CARRIED-FORWARD PREREQUISITES honored here:
     - LMDB map-size bumped to 4 GB (default 10 MB MapFulls at real scale).
     - ColBERT index timeout is corpus-size-aware (V16) — the skeleton's
       index-stage surfaces any genuine failure loudly (no silent RRF-on-2).
     - :max-retries on discovery (reuses the executor primitive) for cold-start
       empty completions.
     - Same local embedding model (all-MiniLM-L6-v2, 384-dim) + ColBERT config
       A1 uses (fairness control — embeddings via the skeleton default).

   USAGE (REPL with :dev:test, OPENROUTER_API_KEY in env ONLY):
     (require '[v09-graph-b-build :as v9])
     (def r (v9/run! {}))                ; full 5-source build
     (v9/print-summary! r)
     (v9/save-capture! r)                ; writes the live-verify doc + artifact

   No mocks. Real Grain event store, real LLM discovery, real local embeddings,
   real ColBERT. No false green — a source that yields nothing, a disconnected
   graph, or a non-terminal skeleton status is reported as-is."
  (:require [ai.obney.orc.ontology.interface :as ontology]
            [ai.obney.orc.ontology.interface.schemas]
            [ai.obney.orc.ontology.core.commands]
            [ai.obney.orc.ontology.core.read-models :as rm]
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
;; The 5 official sources
;; =============================================================================

(def ipeds-db    "/Users/darylroberts/Downloads/output.db")
(def crosswalk-csv "/Users/darylroberts/Downloads/cip_soc_crosswalk.csv")
(def onet-dir    "/Users/darylroberts/Downloads/db_30_1_excel")
(def onet-occupations (str onet-dir "/Occupation Data.xlsx"))
(def wages-csv   "/Users/darylroberts/Desktop/Code/area_51/dspy_notebooks/bryc-workshop/components/recommendations/resources/recommendations/louisiana_occupation_wages.csv")
(def pseo-xlsx   "/Users/darylroberts/Downloads/pseo_la.xlsx")

(def default-model "google/gemini-3-flash-preview")

(def capture-path "docs/build-timeline/live-verify/V09-graph-b-build.md")
(def artifact-path "docs/build-timeline/live-verify/V09-graph-b-artifact.edn")

;; The 5 sources in discovery order. CIP-bearing sources first so cip: concepts
;; exist before the crosswalk + PSEO reference them (concept-before-edge order
;; is not strictly required — create-relationship resolves by URI regardless —
;; but it keeps the graph readable as it grows).
(defn sources []
  [{:name :ipeds      :type :sql   :path ipeds-db}
   {:name :crosswalk  :type :csv   :path crosswalk-csv}
   {:name :onet       :type :excel :path onet-occupations}
   {:name :wages      :type :csv   :path wages-csv}
   {:name :pseo       :type :excel :path pseo-xlsx}])

;; Per-source discovery hints PREPENDED to the default discovery prompt. Each
;; tells the model which entities/keys to mint as SHAREABLE canonical URIs so
;; the graph CONNECTS. These are exploration ORIENTATION, not extraction code —
;; the model still samples by shape and designs its own transform.
(def source-hints
  {:ipeds
   (str "THIS SOURCE is IPEDS (US college data). The tables you care about:\n"
        "  - CIPCodes (CIPCode + CIPTitle) — mint one shareable \"cip:<CIPCode>\" "
        "concept per row, label = CIPTitle. Use the dotted code verbatim "
        "(e.g. \"cip:42.0101\").\n"
        "  - HD2022 (UNITID + INSTNM + CITY + STABBR) — Louisiana institutions "
        "(STABBR = 'LA'). Mint \"unitid:<UNITID>\" concepts, label = INSTNM, put "
        "city in :attributes.\n"
        "  - C2022_A (UNITID + CIPCODE + AWLEVEL + CTOTALT) — a COMPLETIONS row is "
        "a PROGRAM: an institution teaching a CIP at an award level. Mint "
        "\"program:<UNITID>:<CIPCODE>:<AWLEVEL>\" concepts (label from the "
        "institution + CIP, completions count in :attributes).\n"
        "CRITICAL CONNECTIVITY RULE: for EVERY completions row you turn into a "
        "program, you MUST ALSO mint the \"cip:<CIPCODE>\" concept for that SAME "
        "CIPCODE (LEFT JOIN CIPCodes on CIPCode=CIPCODE for the title, or label it "
        "\"CIP <CIPCODE>\" when no title row exists). Then emit edges program -> "
        "\"unitid:<UNITID>\" (predicate \"atInstitution\") and program -> "
        "\"cip:<CIPCODE>\" (predicate \"hasCIP\"). This guarantees every hasCIP "
        "edge RESOLVES to a cip: concept you minted in THIS session. Do NOT mint "
        "cip: concepts from a SEPARATE CIPCodes sample — those won't match the "
        "completions CIPs and the edges will dangle. Use the dotted code verbatim "
        "(e.g. \"cip:42.0101\"); keep the SAME cip: scheme the crosswalk uses so "
        "they MERGE.\n"
        "Use ONE `query` that JOINs C2022_A to HD2022 (STABBR='LA') and LEFT JOINs "
        "CIPCodes for titles, bounded (LIMIT ~150). Do NOT dump tables.")
   :crosswalk
   (str "THIS SOURCE is the CIP<->SOC crosswalk (THE BRIDGE that connects the "
        "program side to the occupation side). Columns: CIP_Code, CIP_Title, "
        "SOC_Code, SOC_Title. For each distinct CIP_Code mint \"cip:<CIP_Code>\" "
        "(SAME scheme IPEDS uses), for each distinct SOC_Code mint "
        "\"soc:<SOC_Code>\" (label = SOC_Title), and for each row emit an EDGE "
        "\"cip:<CIP_Code>\" -> \"soc:<SOC_Code>\" predicate \"cipMapsToSoc\". These "
        "edges are what CONNECT the IPEDS cip: concepts to the occupation side.\n"
        "COVERAGE — this file is sorted by CIP_Code and the first rows are all "
        "low-numbered CIPs (agriculture, 01.xxxx) which the college programs do "
        "NOT use. The CIP families the programs actually use start at these DATA "
        "OFFSETS in this file: family 11 (computer) at offset 588, family 13 "
        "(education) at 802, family 42 (psychology) at 3204, family 51 (health) "
        "at 4164, family 52 (business) at 4721. Pull a window at EACH of those "
        "offsets and CONCATENATE them so the bridge spans the families the "
        "programs use. Keep each call simple:\n"
        "  (def rows (concat (:rows (sample-rows {:limit 60 :offset 588}))\n"
        "                    (:rows (sample-rows {:limit 60 :offset 802}))\n"
        "                    (:rows (sample-rows {:limit 60 :offset 3204}))\n"
        "                    (:rows (sample-rows {:limit 60 :offset 4164}))\n"
        "                    (:rows (sample-rows {:limit 40 :offset 4721}))))\n"
        "Then in ONE final! mint a \"cip:<CIP_Code>\" + \"soc:<SOC_Code>\" concept "
        "and a \"cipMapsToSoc\" edge per row over `rows`.\n"
        "Do NOT use emit-tree!. You MUST emit drafts (an empty final! is a "
        "failure). Go straight to final! after the samples.\n"
        "NOTE: some titles contain commas inside quotes — rely on the parsed row "
        "maps, do not split on raw commas.")
   :onet
   (str "THIS SOURCE is O*NET Occupation Data (the occupation definitions). The "
        "sheet has O*NET-SOC Code + Title + Description columns. The O*NET-SOC "
        "code is the 8-digit form (e.g. 19-3033.00); the SOC code the crosswalk + "
        "wages use is the 6-digit prefix (e.g. 19-3033). Mint \"soc:<6-digit "
        "prefix>\" concepts (label = Title, Description -> :description) so they "
        "MERGE with the crosswalk's soc: concepts. Take the first 6 chars / the "
        "part before the dot of the O*NET-SOC code as the SOC code.")
   :wages
   (str "THIS SOURCE is Louisiana occupation wages (LA-OEWS). Columns include "
        "soc_code, soc_title, median_wage, wage_10th_pct, wage_90th_pct, "
        "employment_2022, growth_pct, education_required. Mint \"soc:<soc_code>\" "
        "concepts (SAME scheme the crosswalk + O*NET use, so they MERGE).\n"
        "MANDATORY — every soc: concept you mint here MUST carry an :attributes "
        "map with the NUMERIC outcomes parsed to numbers: "
        "{:median-wage <number> :wage-10th-pct <number> :wage-90th-pct <number> "
        ":employment-2022 <number> :growth-pct <number>}. The wage value is the "
        "whole point of this source — a concept WITHOUT :attributes is useless "
        "here. Parse the cell strings to numbers (e.g. (Double/parseDouble s) is "
        "NOT available — use (read-string s) only if numeric, else build the "
        "number with clojure.core). Do NOT stringify; do NOT omit :attributes; "
        "do NOT just put the wage in :description. Worked shape per row:\n"
        "  {:uri (str \"soc:\" code) :label title\n"
        "   :attributes {:median-wage wage-num :growth-pct growth-num ...}\n"
        "   :evidence [{:source \"median_wage\" :quote wage-str}]}")
   :pseo
   (str "THIS SOURCE is PSEO (Census post-secondary earnings) for Louisiana "
        "institutions. Use the 'Earnings' sheet. Call (sheet-columns <path> "
        "\"Earnings\") first to see the header (its header row is NOT row 1 — "
        "title/note rows precede it; sheet-columns returns :header-row-index).\n"
        "CRITICAL — the first ~3500 data rows are STATE-LEVEL AGGREGATES "
        "(institution column = '22' meaning 'Institutions in Louisiana', and/or "
        "cipcode = '00' meaning 'All Instructional Programs' / cip_level = 'A' "
        "'All Degree Fields'). These aggregate rows are NOT per-program earnings "
        "and you must SKIP them. The real per-institution + per-CIP rows begin "
        "deeper in the sheet, so sample with an OFFSET: "
        "(sample-rows <path> \"Earnings\" {:limit 40 :offset 3525}) — and you can "
        "page further with larger offsets ({:offset 6000}, {:offset 9000}, ...) "
        "to gather a spread of real programs. A REAL row has an 8-digit "
        "institution code (e.g. '00200500'), a specific 2-digit cipcode (e.g. "
        "'11'), a concrete Degree Award Level (e.g. 'Baccalaureate'), and numeric "
        "earnings.\n"
        "Column positions (0-based, from the header): institution=4, "
        "Degree Award Level=6 (label at 7), cipcode=10 (cip_level=8), "
        "y1_p50_earnings=25, y5_p50_earnings=29, y10_p50_earnings=33. A "
        "suppressed/blank earnings cell shows as nil or '.' — skip those rows.\n"
        "For each real row mint an earnings concept uri "
        "\"pseo:<institution>:<cipcode>:<degree>\" (label = a readable summary), "
        "and put earnings-y1 / earnings-y5 / earnings-y10 as NUMBERS in "
        ":attributes (parse the cell to a number — this is the acceptance-"
        "critical outcome; do NOT stringify and do NOT omit :attributes). The "
        "2-digit cipcode here is a CIP FAMILY (e.g. '11'); to connect to the "
        "dotted cip: scheme other sources use, also carry the raw cipcode in "
        ":attributes as cip-family. Emit an edge from the earnings concept to "
        "\"unitid:<institution>\" predicate \"earningsAtInstitution\" so earnings "
        "CONNECT to the institution + program side.")})

;; =============================================================================
;; Provider + context wiring (mirrors v02_mode_a / s18-live-verify)
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
        dir (str "/tmp/v09-graph-b-" (random-uuid))
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
        ;; Start the engine todo-processors (s18 pattern) so the discovery
        ;; recursive-RLM Phase-2 ticks actually drive.
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
;; Per-source discovery (one source per session — V06 pattern)
;; =============================================================================

(defn- snapshot [ctx oid]
  (let [concepts (filter #(= oid (:ontology-id %)) (rm/get-concepts ctx {}))
        rels (filter #(= oid (:ontology-id %)) (rm/get-relationships ctx))]
    {:concepts concepts :relationships rels}))

(defn discover-source!
  "Run discovery for ONE source against the shared ontology-id, capturing the
   model's drafts VERBATIM, then compile them into events. Returns the per-source
   outcome (discovery status + emitted counts + rlm-trace + the new concepts/edges
   this source added to the graph)."
  [ctx oid {:keys [name] :as source} model budget]
  (let [before (snapshot ctx oid)
        prompt (str (get source-hints name "")
                    "\n\n"
                    ai.obney.orc.ontology.core.rlm-discovery/default-discovery-prompt)
        disc (ontology/run-discovery!
              ctx {:ontology-id oid
                   :sources [source]
                   :discovery-prompt prompt
                   :model model
                   :budget budget})
        ;; Compile drafts -> events only on a real draft yield (no false green).
        compiled (when (= :emitted-drafts (:status disc))
                   (ontology/compile-discovery-source! ctx oid disc))
        after (snapshot ctx oid)
        new-concept-uris (set/difference (set (map :uri (:concepts after)))
                                         (set (map :uri (:concepts before))))]
    {:source name
     :discovery-status (:status disc)
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
;; Graph-structure stats (V08-compatible schema — feeds the V10 diff)
;; =============================================================================

(defn- uri-kind
  "Coarse concept kind by URI scheme — the cross-source key family."
  [uri]
  (let [u (str uri)]
    (cond
      (str/starts-with? u "program:") :program
      (str/starts-with? u "cip:")     :cip
      (str/starts-with? u "soc:")     :soc
      (str/starts-with? u "unitid:")  :institution
      (str/starts-with? u "pseo:")    :earnings
      :else (let [i (str/index-of u ":")] (if i (keyword (subs u 0 i)) :other)))))

(defn graph-stats
  "Capture the graph-structure stats (same schema V08 captures for A1, so V10
   can diff). Counts by kind + predicate, cross-source link counts, a
   connectivity check, axiom counts, and attribute coverage."
  [ctx oid]
  (let [{:keys [concepts relationships]} (snapshot ctx oid)
        concept-uris (set (map :uri concepts))
        by-kind (frequencies (map #(uri-kind (:uri %)) concepts))
        by-pred (frequencies (map :predicate relationships))
        ;; cross-source links = an edge whose endpoints are DIFFERENT kinds.
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
                    ;; get-axioms returns a map of families
                    ;; (:disjointness / :characteristics / :inverse-of /
                    ;; :sub-property-of / :chains) — count the entries across
                    ;; whichever families carry a collection.
                    (reduce + 0 (map (fn [[_ v]]
                                       (cond (sequential? v) (count v)
                                             (map? v) (count v)
                                             (set? v) (count v)
                                             :else 0))
                                     axioms))
                    0)
     :axioms axioms}))

;; =============================================================================
;; Connectivity proof — a real multi-hop program->CIP->SOC->earnings path
;; =============================================================================

(defn- edges-from [rels uri] (filter #(= uri (:source-uri %)) rels))
(defn- edges-to   [rels uri] (filter #(= uri (:target-uri %)) rels))

(defn connectivity-proof
  "Walk a REAL multi-hop path read back from the graph:
     program --hasCIP--> cip --cipMapsToSoc--> soc  (+ wage attrs on soc)
     program --atInstitution--> unitid <--earningsAtInstitution-- pseo (earnings)
   Returns the first complete chain found, with the concept facts at each hop, or
   a structured 'no path found' explaining where the chain broke (no false green)."
  [ctx oid]
  (let [{:keys [concepts relationships]} (snapshot ctx oid)
        by-uri (into {} (map (juxt :uri identity) concepts))
        programs (filter #(= :program (uri-kind (:uri %))) concepts)
        chain
        (some
         (fn [prog]
           (let [p-uri (:uri prog)
                 cip-edge (first (filter #(= :cip (uri-kind (:target-uri %)))
                                         (edges-from relationships p-uri)))
                 cip-uri (:target-uri cip-edge)
                 soc-edge (when cip-uri
                            (first (filter #(= :soc (uri-kind (:target-uri %)))
                                           (edges-from relationships cip-uri))))
                 soc-uri (:target-uri soc-edge)
                 soc (get by-uri soc-uri)
                 ;; earnings reached via the institution this program is at
                 inst-edge (first (filter #(= :institution (uri-kind (:target-uri %)))
                                          (edges-from relationships p-uri)))
                 inst-uri (:target-uri inst-edge)
                 earn-edge (when inst-uri
                             (first (filter #(= :earnings (uri-kind (:source-uri %)))
                                            (edges-to relationships inst-uri))))
                 earn (get by-uri (:source-uri earn-edge))]
             (when (and cip-uri soc-uri)
               {:program (select-keys prog [:uri :label :attributes])
                :program->cip (select-keys cip-edge [:source-uri :predicate :target-uri])
                :cip (select-keys (get by-uri cip-uri) [:uri :label])
                :cip->soc (select-keys soc-edge [:source-uri :predicate :target-uri])
                :soc (select-keys soc [:uri :label :attributes])
                :program->institution (some-> inst-edge (select-keys [:source-uri :predicate :target-uri]))
                :institution (some-> (get by-uri inst-uri) (select-keys [:uri :label]))
                :earnings-concept (some-> earn (select-keys [:uri :label :attributes]))
                :earnings->institution (some-> earn-edge (select-keys [:source-uri :predicate :target-uri]))})))
         programs)]
    (or chain
        {:no-complete-chain true
         :program-count (count programs)
         :note "No program->cip->soc(+earnings) chain found — see graph-stats cross-source-links for where the connection broke."})))

;; =============================================================================
;; Retrievability — labeled hybrid-search hits (V15 label enrichment)
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
  "Run the full V09 graph-B build. Required env: OPENROUTER_API_KEY.
   Options:
     :model            — OpenRouter model (default gemini-3-flash-preview).
     :budget           — discovery budget per source (default generous).
     :only             — vector of source :name keys to run a subset (debug).
   Returns the full result map for capture."
  [{:keys [model budget only]
    :or {model default-model
         budget {:max-iterations 10 :total-budget-ms 600000 :max-retries 2}}}]
  (let [ctx (make-ctx)
        oid (random-uuid)
        srcs (cond->> (sources) only (filter #(some #{(:name %)} only)))]
    (try
      (register-openrouter! model)
      (println "=== V09 GRAPH B BUILD ===")
      (println "Ontology-id:" oid "  model:" model)
      (println "Sources:" (mapv :name srcs))
      (let [per-source
            (mapv (fn [src]
                    (println "\n>>> discovering source:" (:name src)
                             "(" (name (:type src)) ")")
                    (let [r (discover-source! ctx oid src model budget)]
                      (println "    status:" (:discovery-status r)
                               " emitted c/r/a:"
                               (:emitted-concepts r) "/"
                               (:emitted-relationships r) "/"
                               (:emitted-axioms r)
                               " graph now:" (:concepts-after r) "concepts /"
                               (:relationships-after r) "rels")
                      (when (:session-error r)
                        (println "    SESSION ERROR:" (:session-error r)))
                      r))
                  srcs)
            ;; ONE deterministic skeleton build over the accumulated graph.
            ;; parse no-op stub (events already landed) -> normalize -> dedup ->
            ;; validate -> embed (V01) -> index (ColBERT) -> CQ exit. No spec is
            ;; recorded, so exit-criterion returns :spec-absent? -> terminal.
            ;; :validation :halt-on :none — surface violations as warnings, run
            ;; the full contract to a terminal status (failures still loud).
            _ (println "\n>>> running deterministic skeleton over the accumulated graph")
            skeleton ((requiring-resolve
                       'ai.obney.orc.ontology.core.deterministic-skeleton/build!)
                      ctx {:ontology-id oid
                           :sources [{:type :inline-concepts :concepts []}]
                           :validation {:halt-on :none}})
            _ (println "    skeleton status:" (:status skeleton)
                       " stages:" (:stages-run skeleton))
            _ (println "\n>>> capturing graph-structure stats")
            stats (graph-stats ctx oid)
            _ (println "    concepts:" (:concept-count stats)
                       " rels:" (:relationship-count stats)
                       " cross-source links:" (:cross-source-link-total stats)
                       " earnings/wage concepts:" (:earnings-or-wage-bearing-concepts stats))
            _ (println "\n>>> connectivity proof (multi-hop path read-back)")
            conn (connectivity-proof ctx oid)
            _ (println "    " (if (:no-complete-chain conn) "NO COMPLETE CHAIN" "CHAIN FOUND"))
            _ (println "\n>>> retrievability probes (labeled hybrid-search)")
            probes (retrievability-probes! ctx oid)]
        (println "\n=== DONE ===")
        {:ontology-id oid
         :model model
         :budget budget
         :sources (mapv :name srcs)
         :per-source per-source
         :skeleton skeleton
         :stats stats
         :connectivity conn
         :probes probes
         ;; full graph for the artifact dump
         ::concepts (:concepts (snapshot ctx oid))
         ::relationships (:relationships (snapshot ctx oid))})
      (finally (stop-ctx ctx)))))

;; =============================================================================
;; Capture + artifact
;; =============================================================================

(defn save-artifact!
  "Serialize concepts + relationships + stats to a loadable EDN artifact so V10
   (diff) and V12 (exploration) can load graph B without rebuilding."
  [result]
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
  (println "\n================ V09 GRAPH B SUMMARY ================")
  (println "ontology-id:" (:ontology-id r) " model:" (:model r))
  (println "\n--- per-source ---")
  (doseq [s (:per-source r)]
    (println (format "  %-10s status=%s emitted c/r/a=%d/%d/%d trace=%s"
                     (name (:source s)) (:discovery-status s)
                     (:emitted-concepts s) (:emitted-relationships s) (:emitted-axioms s)
                     (pr-str (:rlm-trace s)))))
  (println "\n--- skeleton ---")
  (println "  status:" (get-in r [:skeleton :status])
           " stages:" (get-in r [:skeleton :stages-run]))
  (println "  concepts-count:" (get-in r [:skeleton :concepts-count])
           " spec-absent?:" (get-in r [:skeleton :spec-absent?]))
  (println "\n--- graph stats ---")
  (pp/pprint (dissoc (:stats r) :axioms))
  (println "\n--- connectivity proof ---")
  (pp/pprint (:connectivity r))
  (println "\n--- retrievability ---")
  (pp/pprint (:probes r)))

(defn save-capture!
  "Write the live-verify capture doc (verbatim stats/proof/probes) + the artifact."
  [r]
  (save-artifact! r)
  (io/make-parents capture-path)
  (spit capture-path
        (str "# V09 — Graph B build (new builder, 5 sources) — LIVE VERIFY\n\n"
             "**Date:** 2026-06-15. **Branch:** `feature/ontology-architecture`.\n"
             "**Model:** `" (:model r) "` (real OpenRouter). **Embeddings:** local "
             "all-MiniLM-L6-v2 (DJL, 384-dim). **ColBERT:** real index. **No mocks.**\n\n"
             "Ontology-id: `" (:ontology-id r) "`. Sources (each builder assembles its "
             "own program set + its own embeddings; the hand-made embeddings CSV is "
             "EXCLUDED): " (pr-str (:sources r)) ".\n\n"
             "Artifact (loadable by V10/V12): `" artifact-path "`.\n\n"
             "## Per-source ingestion outcome (verbatim)\n\n```clojure\n"
             (with-out-str (pp/pprint (mapv #(dissoc % :usage) (:per-source r))))
             "```\n\n## Skeleton terminal status\n\n```clojure\n"
             ;; :dedup-review-required can be hundreds of identical
             ;; budget-exhausted entries (dedup ran with :llm-budget 0); keep
             ;; the COUNT + one sample so the doc isn't 3000 lines of noise.
             (with-out-str (pp/pprint
                            (let [sk (dissoc (:skeleton r) :artifacts)
                                  drr (:dedup-review-required sk)]
                              (cond-> sk
                                (seq drr)
                                (assoc :dedup-review-required
                                       {:count (count drr)
                                        :sample (first drr)
                                        :note "elided — all entries identical (dedup ran with :llm-budget 0)"})))))
             "```\n\n## Graph-structure stats (V08 schema — feeds V10 diff)\n\n```clojure\n"
             (with-out-str (pp/pprint (:stats r)))
             "```\n\n## Connectivity proof (multi-hop path read back from the graph)\n\n```clojure\n"
             (with-out-str (pp/pprint (:connectivity r)))
             "```\n\n## Retrievability — labeled hybrid-search hits\n\n```clojure\n"
             (with-out-str (pp/pprint (:probes r)))
             "```\n"))
  (println "Capture written:" capture-path)
  (println "Artifact written:" artifact-path)
  capture-path)

(comment
  (require '[v09-graph-b-build :as v9] :reload)
  (def r (v9/run! {}))
  (v9/print-summary! r)
  (v9/save-capture! r))
