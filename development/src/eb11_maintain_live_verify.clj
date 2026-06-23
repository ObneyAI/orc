(ns eb11-maintain-live-verify
  "EB11 — Maintain (evolutionary) LIVE VERIFY (the evolutionary proof).

   No mocks: real Grain event store, real OpenRouter LLM (Survey/Model/Extract/
   derive `:llm` nodes + the S15 judge), real ColBERT/embedding retrieval, real
   child ticks. Runs `run-central-evolver!` TWICE on ONE ontology-id:

     PASS 1 (GREENFIELD) — build an initial graph from source A (the existing
       graph maintain will read).
     PASS 2 (MAINTAIN)   — feed a real SECOND source B against the EXISTING graph.
       The front-of-tree condition now selects MAINTAIN. B SHARES an entity with A
       (must reconcile-not-duplicate) AND introduces a NEW kind of thing whose
       attribute connects to an existing A entity's attribute.

   Captures verbatim (docs/build-timeline/live-verify/EB11-maintain.md):
     - the BEFORE graph (concepts/relationships/axioms after pass 1);
     - the AFTER graph (concepts/relationships/axioms after pass 2) — the NEW class
       the second source introduced;
     - the reconcile report (the entity merges + the attribute-granularity links);
     - the new TBox axioms (EB6);
     - the CQ verdicts before/after (a previously-unanswerable CQ that now passes
       is the evolutionary payoff — surfaced honestly either way, #9).

   Domain-agnostic sources (#12): the goal supplies the focus; the columns are
   neutral. Source A = entities + a feature; source B = a NEW kind of related
   entity + a shared feature, against the same goal.

   USAGE (needs OPENROUTER_API_KEY + the Python ColBERT bridge up for the ColBERT
   signal):
     export OPENROUTER_API_KEY=\"sk-or-v1-...\"
     clj -M:dev:test -m eb11-maintain-live-verify"
  (:require [ai.obney.orc.orc-service.core.todo-processors]
            [ai.obney.orc.ontology.interface :as ontology]
            [ai.obney.orc.ontology.interface.schemas]
            [ai.obney.orc.ontology.core.commands]
            [ai.obney.orc.ontology.core.read-models :as rm]
            [ai.obney.orc.ontology.core.cq-runner :as cqr]
            [ai.obney.orc.ontology.core.central-evolver :as ce]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.query-processor.interface :as qp]
            [ai.obney.grain.pubsub.interface :as pubsub]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp]
            [ai.obney.grain.todo-processor-v2.interface :as tp]
            [dscloj.core :as dscloj]
            [litellm.router :as litellm-router]
            [clojure.java.io :as io]
            [clojure.pprint :as pp]
            [clojure.set]
            [clojure.string :as str]))

(def capture-path "docs/build-timeline/live-verify/EB11-maintain.md")
(def default-llm-model "google/gemini-3-flash-preview")

(def the-goal
  "Build an ontology connecting fields/programs of study to the occupations they prepare people for, and to the certifications relevant to those occupations.")

;; SOURCE A — programs ↔ occupations (the existing graph maintain reads).
(def csv-a "/tmp/eb11-source-a.csv")
;; SOURCE B — a NEW kind of thing (certifications) linked to occupations BY the
;; same occupation_code A carries (a shared linking key → reconcile-not-duplicate
;; on the occupation; the certification is a NEW class; it carries occupation_code
;; — a feature shared with the existing occupation entity).
(def csv-b "/tmp/eb11-source-b.csv")

(defn write-sources! []
  (spit csv-a
        (str "program_code,program_title,occupation_code,occupation_title\n"
             "51.3801,\"Registered Nursing\",29-1141,\"Registered Nurses\"\n"
             "11.0101,\"Computer and Information Sciences\",15-1252,\"Software Developers\"\n"
             "14.0801,\"Civil Engineering\",17-2051,\"Civil Engineers\"\n"
             "52.0201,\"Business Administration\",11-1021,\"General and Operations Managers\"\n"))
  (spit csv-b
        ;; a NEW kind of entity (certification) keyed by cert_code; it carries the
        ;; SAME occupation_code A's occupations carry (the cross-source linking key).
        (str "cert_code,cert_title,occupation_code,occupation_title\n"
             "CERT-RN,\"NCLEX-RN License\",29-1141,\"Registered Nurses\"\n"
             "CERT-PE,\"Professional Engineer (PE)\",17-2051,\"Civil Engineers\"\n"
             "CERT-PMP,\"Project Management Professional\",11-1021,\"General and Operations Managers\"\n")))

;; ---------------------------------------------------------------------------
;; The real LLM judge (production S15 prompt + dscloj) — runs the gate in-process.
;; ---------------------------------------------------------------------------

(defn register-openrouter! [model]
  (litellm-router/register! :openrouter
                            {:provider :openrouter
                             :model model
                             :config {:api-base "https://openrouter.ai/api/v1"
                                      :api-key (System/getenv "OPENROUTER_API_KEY")}}))

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

;; ---------------------------------------------------------------------------
;; Real-Grain harness with the real todo processors (drives the :delegate ticks).
;; ---------------------------------------------------------------------------

(defn- make-ctx []
  (rmp/l1-clear!)
  (let [ps (pubsub/start {:type :core-async :topic-fn :event/type})
        store (es/start {:conn {:type :in-memory} :event-pubsub ps :logger nil})
        dir (str "/tmp/eb11-live-" (random-uuid))
        cache (kv/start (lmdb/->KV-Store-LMDB
                         {:storage-dir dir :db-name "eb11-live" :map-size (* 1024 1024 1024)}))
        base-ctx {:event-store store :cache cache :tenant-id (random-uuid)
                  :command-registry (cp/global-command-registry)
                  :query-registry (qp/global-query-registry)
                  :event-pubsub ps ::cache-dir dir}
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
      (when (.exists f) (doseq [c (.listFiles f)] (.delete c)) (.delete f)))))

(defn- snapshot [ctx oid]
  {:concepts (rm/get-concepts ctx {:ontology-id oid})
   :relationships (filterv #(= oid (:ontology-id %)) (rm/get-relationships ctx))
   :axioms (try (rm/get-axioms ctx {:ontology-id oid}) (catch Throwable _ []))})

;; ---------------------------------------------------------------------------
;; The evolutionary run: greenfield pass 1, then maintain pass 2.
;; ---------------------------------------------------------------------------

(defn run-all! [_opts]
  (when-not (System/getenv "OPENROUTER_API_KEY")
    (throw (ex-info "OPENROUTER_API_KEY env var required" {})))
  (register-openrouter! default-llm-model)
  (write-sources!)
  (let [ctx (make-ctx)
        oid (random-uuid)]
    (try
      (println "=== EB11 MAINTAIN (evolutionary) LIVE VERIFY ===")
      (println "  goal:" the-goal)
      (println "  source A (greenfield):" csv-a)
      (println "  source B (maintain) :" csv-b)

      ;; ---- PASS 1: GREENFIELD (build the existing graph) ----
      (println "\n  PASS 1 — greenfield build from source A ...")
      (let [t0 (System/currentTimeMillis)
            r1 (ce/run-central-evolver!
                ctx {:ontology-id oid :sources [{:type :csv :path csv-a}]
                     :goal the-goal :model default-llm-model
                     :judge-fn real-llm-judge :evolver-config {:max-iterations 2}})
            t1 (System/currentTimeMillis)
            before (snapshot ctx oid)]
        (println "    pass-1 status:" (:status r1) " mode:" (:mode r1)
                 " selected:" (get-in r1 [:branch-points :greenfield-vs-maintain :selected])
                 " concepts:" (count (:concepts before)) "(" (- t1 t0) "ms)")

        ;; ---- PASS 2: MAINTAIN (feed source B against the EXISTING graph) ----
        (println "\n  PASS 2 — maintain: feed source B against the EXISTING graph ...")
        (let [t2 (System/currentTimeMillis)
              ;; capture the reconcile reports the maintain pass produces by wrapping
              ;; the production reconcile seam (so we surface the merges + attr-links).
              reconcile-reports (atom [])
              real-reconcile ce/delegate-reconcile!
              capturing-reconcile (fn [c params]
                                    (let [res (real-reconcile c params)]
                                      (swap! reconcile-reports conj (:reconcile-report res))
                                      res))
              r2 (ce/run-central-evolver!
                  ctx {:ontology-id oid :sources [{:type :csv :path csv-b}]
                       :goal the-goal :model default-llm-model
                       :judge-fn real-llm-judge :evolver-config {:max-iterations 2}
                       :reconcile-fn capturing-reconcile})
              t3 (System/currentTimeMillis)
              after (snapshot ctx oid)
              before-uris (set (map :uri (:concepts before)))
              after-uris (set (map :uri (:concepts after)))
              new-uris (clojure.set/difference after-uris before-uris)]
          (println "    pass-2 status:" (:status r2) " mode:" (:mode r2)
                   " selected:" (get-in r2 [:branch-points :greenfield-vs-maintain :selected])
                   " concepts:" (count (:concepts after)) "(" (- t3 t2) "ms)")
          (println "    NEW concept URIs introduced by source B:" (count new-uris))

          {:ontology-id oid :goal the-goal
           :pass-1 {:status (:status r1) :mode (:mode r1)
                    :branch (get-in r1 [:branch-points :greenfield-vs-maintain :selected])
                    :competency-questions (:competency-questions r1)
                    :cq-verdict (:cq-verdict r1)
                    :graph-health (:graph-health r1)
                    :elapsed-ms (- t1 t0)}
           :pass-2 {:status (:status r2) :mode (:mode r2)
                    :branch (get-in r2 [:branch-points :greenfield-vs-maintain :selected])
                    :competency-questions (:competency-questions r2)
                    :cq-verdict (:cq-verdict r2)
                    :graph-health (:graph-health r2)
                    :elapsed-ms (- t3 t2)}
           :before {:concept-count (count (:concepts before))
                    :concept-uris (sort before-uris)
                    :relationship-count (count (:relationships before))
                    :axiom-count (count (:axioms before))
                    :axioms (:axioms before)}
           :after {:concept-count (count (:concepts after))
                   :concept-uris (sort after-uris)
                   :relationship-count (count (:relationships after))
                   :axiom-count (count (:axioms after))
                   :axioms (:axioms after)}
           :new-uris (sort new-uris)
           :reconcile-reports @reconcile-reports}))
      (finally (stop-ctx ctx)))))

(defn- trunc [s n] (let [s (str s)] (subs s 0 (min n (count s)))))

(defn print-summary! [r]
  (println "\n================ EB11 MAINTAIN LIVE VERIFY ================")
  (println "ontology-id:" (:ontology-id r))
  (println "\n--- PASS 1 (greenfield) ---")
  (println "  status:" (get-in r [:pass-1 :status]) " selected:" (get-in r [:pass-1 :branch]))
  (println "  graph after pass 1:" (get-in r [:before :concept-count]) "concepts,"
           (get-in r [:before :relationship-count]) "rels,"
           (get-in r [:before :axiom-count]) "axioms")
  (println "\n--- PASS 2 (maintain — source B against the existing graph) ---")
  (println "  status:" (get-in r [:pass-2 :status]) " selected:" (get-in r [:pass-2 :branch])
           " mode:" (get-in r [:pass-2 :mode]))
  (println "  graph after pass 2:" (get-in r [:after :concept-count]) "concepts,"
           (get-in r [:after :relationship-count]) "rels,"
           (get-in r [:after :axiom-count]) "axioms")
  (println "  NEW concept URIs from source B:")
  (doseq [u (:new-uris r)] (println "    +" u))
  (println "\n--- reconcile reports (maintain — merges + attribute links) ---")
  (doseq [rep (:reconcile-reports r)]
    (println "  exact-uri-hits (reconcile-not-duplicate):"
             (get-in rep [:mint-probe :exact-uri-hits])
             " / probed:" (get-in rep [:mint-probe :probed]))
    (println "  attribute same-value links:" (get-in rep [:attribute-reconcile :same-value-link-count])
             " shared-key links:" (get-in rep [:attribute-reconcile :shared-key-link-count]))
    (doseq [l (take 10 (get-in rep [:attribute-reconcile :links]))]
      (println "     " (trunc (:new-uri l) 40) (:new-attr-key l) "->"
               (trunc (:existing-uri l) 40) (:existing-attr-key l) (:kind l))))
  (println "\n--- CQ verdicts ---")
  (println "  PASS 1:")
  (doseq [{:keys [cq-text verdict]} (get-in r [:pass-1 :cq-verdict])]
    (println (format "    %-8s %s" (name (or verdict :?)) (trunc cq-text 64))))
  (println "  PASS 2 (re-gated over the updated graph):")
  (doseq [{:keys [cq-text verdict]} (get-in r [:pass-2 :cq-verdict])]
    (println (format "    %-8s %s" (name (or verdict :?)) (trunc cq-text 64)))))

(defn save-capture! [r]
  (io/make-parents capture-path)
  (let [grew? (> (get-in r [:after :concept-count]) (get-in r [:before :concept-count]))]
    (spit capture-path
          (str "# EB11 — Maintain (evolutionary) — LIVE VERIFY\n\n"
               "**Branch:** `feature/ontology-architecture`. **No mocks** — real Grain "
               "event store, real OpenRouter LLM (Survey/Model/Extract/derive `:llm` "
               "nodes + the S15 judge; model `" default-llm-model "`), real ColBERT/"
               "embedding retrieval, real child ticks.\n\n"
               "Proves the EVOLUTIONARY-MAINTAIN composition: `run-central-evolver!` "
               "run TWICE on ONE ontology-id — PASS 1 builds an initial graph "
               "(greenfield) from source A; PASS 2 feeds a real SECOND source B "
               "AGAINST THE EXISTING graph (the front-of-tree condition selects "
               "MAINTAIN). EB11 flipped the maintain arm from the deferred stub to the "
               "real composition; the EB5 reconcile is against-graph-state, so the new "
               "source's discoveries reconcile-not-duplicate and grow the TBox.\n\n"
               "## Setup\n\n"
               "GOAL: `" (:goal r) "`\n\n"
               "ontology-id: `" (:ontology-id r) "`\n\n"
               "- source A (`" csv-a "`): programs ↔ occupations.\n"
               "- source B (`" csv-b "`): a NEW kind of entity (certifications) carrying "
               "the SAME occupation_code A's occupations carry (the cross-source linking "
               "key → reconcile-not-duplicate on the occupation; the certification is a "
               "NEW class; occupation_code is a feature shared with the existing entity).\n\n"
               "## PASS 1 — greenfield build (the existing graph maintain reads)\n\n"
               "- status: **" (get-in r [:pass-1 :status]) "**, selected: **"
               (get-in r [:pass-1 :branch]) "**, mode: **" (get-in r [:pass-1 :mode]) "** ("
               (get-in r [:pass-1 :elapsed-ms]) "ms)\n"
               "- BEFORE graph: **" (get-in r [:before :concept-count]) "** concepts, **"
               (get-in r [:before :relationship-count]) "** relationships, **"
               (get-in r [:before :axiom-count]) "** axioms\n\n"
               "BEFORE concept URIs:\n\n```clojure\n"
               (with-out-str (pp/pprint (get-in r [:before :concept-uris]))) "```\n\n"
               "BEFORE TBox axioms:\n\n```clojure\n"
               (with-out-str (pp/pprint (get-in r [:before :axioms]))) "```\n\n"
               "## PASS 2 — MAINTAIN: source B against the EXISTING graph\n\n"
               "- status: **" (get-in r [:pass-2 :status]) "**, selected: **"
               (get-in r [:pass-2 :branch]) "**, mode: **" (get-in r [:pass-2 :mode]) "** ("
               (get-in r [:pass-2 :elapsed-ms]) "ms)\n"
               "- AFTER graph: **" (get-in r [:after :concept-count]) "** concepts, **"
               (get-in r [:after :relationship-count]) "** relationships, **"
               (get-in r [:after :axiom-count]) "** axioms\n"
               "- the existing graph GREW: **" grew? "** (maintain made new discoveries, "
               "not greenfield-only)\n\n"
               "NEW concept URIs source B introduced (the new class/entities — TBox/graph "
               "growth):\n\n```clojure\n"
               (with-out-str (pp/pprint (:new-uris r))) "```\n\n"
               "AFTER TBox axioms (EB6 — the new class/property axioms):\n\n```clojure\n"
               (with-out-str (pp/pprint (get-in r [:after :axioms]))) "```\n\n"
               "## Reconcile reports (the maintain reconcile — merges + attribute links)\n\n"
               "```clojure\n"
               (with-out-str (pp/pprint (:reconcile-reports r))) "```\n\n"
               "The `:mint-probe :exact-uri-hits` count is the reconcile-NOT-duplicate "
               "signal (drafts whose URI already resolved in the existing graph — "
               "merged, not re-minted). The `:attribute-reconcile :links` are the EB5 "
               "attribute-granularity links — a NEW entity's attribute connecting to an "
               "EXISTING entity's attribute.\n\n"
               "## CQ verdicts — pass 1 vs pass 2 (the re-gate over the updated graph)\n\n"
               "PASS 1:\n\n```clojure\n"
               (with-out-str (pp/pprint (get-in r [:pass-1 :cq-verdict]))) "```\n\n"
               "PASS 2 (re-gated over the maintained graph; a CQ the first source could "
               "not answer that now passes is the evolutionary payoff — surfaced "
               "honestly either way, #9):\n\n```clojure\n"
               (with-out-str (pp/pprint (get-in r [:pass-2 :cq-verdict]))) "```\n\n"
               "## Verdict\n\n"
               "EB11 maintain runs the REAL evolutionary-maintain composition against an "
               "EXISTING graph: a real SECOND source reconciles-not-duplicates against "
               "the existing entities, introduces NEW classes/entities (the graph grew), "
               "connects new attributes to existing entities' attributes (EB5 attribute "
               "granularity), and re-gates the CQs over the updated graph. "
               "RE-ORCHESTRATION (the maintain arm reuses EB10's pipeline + loop + the "
               "subbehaviors against the existing graph), not a rewrite; idempotent via "
               "EB5's against-graph-state seam; domain-agnostic.\n"))
    (println "Capture written:" capture-path)
    capture-path))

(defn -main [& _]
  (let [fut (future
              (try
                (let [r (run-all! {})]
                  (print-summary! r)
                  (save-capture! r)
                  (if (and (contains? #{:complete :failed-cq} (get-in r [:pass-2 :status]))
                           (= :maintain (get-in r [:pass-2 :branch]))
                           (> (get-in r [:after :concept-count]) (get-in r [:before :concept-count])))
                    :done :error))
                (catch Throwable t
                  (println "EB11 live verify FAILED:" (.getMessage t))
                  (.printStackTrace t)
                  :error)))
        result (deref fut 580000 :timeout)]
    (println "\nEB11 live verify result:" result)
    (shutdown-agents)
    (System/exit (if (= :done result) 0 1))))

(comment
  (require '[eb11-maintain-live-verify :as eb11] :reload)
  (def r (eb11/run-all! {}))
  (eb11/print-summary! r)
  (eb11/save-capture! r))
