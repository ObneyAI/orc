(ns dt5-requirements-cq-node-live-verify
  "DT5 — graph-level Requirements / competency-question node LIVE VERIFY.

   Proves the CQ node, run AFTER profiling, derives GROUNDED + GOAL-ANCHORED
   competency questions from the GOAL ⨯ the real source profiles, PERSISTS them as
   the S14 ORSD spec build!'s S15 exit-criterion reads, and is HITL-reviewable /
   consumer-overridable.

   No mocks: real OpenRouter LLM (gemini-3-flash-preview), real SCI sandbox
   executor, real Grain in-memory event store + the real S14 command → event →
   projection path (so the spec read-back is the REAL projection build! sees).

   Two real profiles feed the graph-level node: the captured DT2 CSV crosswalk
   profile (prose-string value shapes — proves tolerant reading) + a LIVE DT2 SQL
   profile of the real IPEDS DB (so at least one profile is a fresh real run). The
   node then:
     1. DERIVES CQs from goal ⨯ both profiles, persists them, reads the spec back.
     2. Confirms a CONSUMER-SUPPLIED CQ set OVERRIDES the derived set + persists.

   USAGE (REPL with :dev:test, OPENROUTER_API_KEY in env ONLY):
     (require '[dt5-requirements-cq-node-live-verify :as dt5])
     (def r (dt5/run-all! {}))
     (dt5/save-capture! r)"
  (:require [ai.obney.orc.ontology.interface :as ontology]
            [ai.obney.orc.ontology.interface.schemas]
            [ai.obney.orc.ontology.core.commands]
            [ai.obney.orc.ontology.core.rlm-discovery :as rlm-discovery]
            [ai.obney.orc.ontology.core.discovery-tree :as dt]
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
            [clojure.pprint :as pp]))

(def ipeds-db "/Users/darylroberts/Downloads/output.db")
(def default-model "google/gemini-3-flash-preview")
(def capture-path "docs/build-timeline/live-verify/DT5-cq.md")

;; The runtime goal anchors the CQs. It names the connection the graph must
;; answer for (programs ↔ occupations) and a scope (Louisiana). NO column/table/
;; code names — the node grounds CQs in the profiles, anchors them in the goal.
(def goal
  (str "Build an ontology connecting fields/programs of study to the occupations "
       "they prepare people for, so we can recommend occupations for a Louisiana "
       "student's chosen program and trace which programs lead to a target "
       "occupation."))

;; Captured DT2 CSV crosswalk profile (VERBATIM from DT2-profile.md) — prose-string
;; value shapes prove the node reads profiles tolerantly. No invention.
(def captured-csv-profile
  {:entity-candidates
   "Academic Programs (CIP), Occupational Titles (SOC), Professional Occupations, Crosswalk/Alignment mappings."
   :identifying-keys
   "'CIPCode' (or 'CIP2020Code'), 'SOCCode' (or 'SOC2018Code')"
   :scope-fields "'CIPTitle', 'SOCTitle'"
   :linking-keys "'CIPCode', 'CIP2020Code', 'SOCCode', 'SOC2018Code'"
   :grain-signals
   "The dataset represents a many-to-many relationship mapping. Repeating keys in both CIP and SOC columns indicate that one program can lead to many occupations, and one occupation can be entered via many programs."
   :sample
   [{"CIP_Code" "01.0000" "CIP_Title" "Agriculture, General."
     "SOC_Code" "19-1011" "SOC_Title" "Animal Scientists"}
    {"CIP_Code" "01.0000" "CIP_Title" "Agriculture, General."
     "SOC_Code" "19-1012" "SOC_Title" "Food Scientists and Technologists"}]})

(def supplied-cqs
  ["Which specific occupations can a Louisiana graduate of a given program enter?"
   "Which programs at Louisiana institutions lead to a given target occupation?"])

(defn- register-openrouter! [model]
  (let [api-key (or (System/getenv "OPENROUTER_API_KEY")
                    (throw (ex-info "OPENROUTER_API_KEY not set (env var only)" {})))
        base {:provider :openrouter :model model
              :config {:api-base "https://openrouter.ai/api/v1" :api-key api-key}}]
    (litellm-router/register! :openrouter base)
    (litellm-router/register! (keyword (str "openrouter/" model)) base)))

(defn make-ctx []
  (rmp/l1-clear!)
  (let [ps (pubsub/start {:type :core-async :topic-fn :event/type})
        store (es/start {:conn {:type :in-memory} :event-pubsub ps :logger nil})
        dir (str "/tmp/dt5-live-" (random-uuid))
        cache (kv/start (lmdb/->KV-Store-LMDB
                         {:storage-dir dir :db-name "dt5-live"
                          :map-size (* 2 1024 1024 1024)}))
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

(defn stop-ctx [ctx]
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

(defn run-all! [{:keys [model budget] :or {model default-model}}]
  (register-openrouter! model)
  (let [budget (or budget {:max-iterations 10 :total-budget-ms 360000 :max-retries 3})
        c (make-ctx)]
    (try
      (let [;; --- LIVE DT2 SQL profile of the real IPEDS DB ---
            _ (println "=== DT5: live DT2 Profile node on the real IPEDS DB ===")
            sql-source {:name :ipeds :type :sql :path ipeds-db}
            c* (assoc c :granted-ontology-id (random-uuid) :ontology-id (random-uuid))
            sql-profile-r (rlm-discovery/run-node-session!
                           c* {:node-name :profile
                               :instruction (dt/profile-node-prompt goal :sql)
                               :source sql-source
                               :writes dt/profile-contract-keys
                               :focused-prompt? true
                               :model model :budget budget})
            sql-profile (:output sql-profile-r)
            _ (println "sql profile status:" (:status sql-profile-r))
            profiles [captured-csv-profile sql-profile]

            ;; --- A. DERIVE CQs (graph-level, AFTER profiling) + persist + read back ---
            oid-derive (random-uuid)
            _ (println "=== DT5 A: derive CQs from goal ⨯ profiles, persist as ORSD spec ===")
            derive-r (dt/requirements-cq-node!
                      c {:ontology-id oid-derive
                         :goal goal
                         :profiles profiles
                         :model model :budget budget})
            _ (Thread/sleep 150)
            spec-after-derive (ontology/get-ontology-spec c oid-derive)

            ;; --- B. CONSUMER OVERRIDE: supplied CQs override + persist ---
            oid-supplied (random-uuid)
            _ (println "=== DT5 B: consumer-supplied CQs override derivation ===")
            supplied-r (dt/requirements-cq-node!
                        c {:ontology-id oid-supplied
                           :goal goal
                           :profiles profiles
                           :cqs supplied-cqs
                           :model model :budget budget})
            _ (Thread/sleep 150)
            spec-after-supplied (ontology/get-ontology-spec c oid-supplied)]
        {:goal goal
         :sql-profile-status (:status sql-profile-r)
         :sql-profile sql-profile
         :csv-profile captured-csv-profile
         :derive derive-r
         :spec-after-derive spec-after-derive
         :supplied supplied-r
         :spec-after-supplied spec-after-supplied})
      (finally (stop-ctx c)))))

(defn save-capture! [r]
  (io/make-parents capture-path)
  (let [d (:derive r)
        s (:supplied r)]
    (spit
     capture-path
     (str
      "# DT5 — Requirements / competency-question node (graph-level) — LIVE VERIFY\n\n"
      "**Date:** 2026-06-17. **Branch:** `feature/ontology-architecture`.\n"
      "**Model:** `" default-model "` (real OpenRouter). **No mocks.**\n"
      "**Type:** HITL — the derived CQs below are surfaced for human review/override.\n\n"
      "A GRAPH-LEVEL node that runs AFTER every source is profiled: it derives "
      "competency questions from the GOAL ⨯ the source profiles (grounded in what "
      "the sources contain + anchored to the goal), persists them as the S14 ORSD "
      "spec build!'s S15 exit-criterion judges, and surfaces them for HITL review. "
      "A consumer-supplied CQ set overrides/seeds the derived set.\n\n"
      "GOAL (anchors the CQs; the scope lives here, not in the node):\n\n> "
      (:goal r) "\n\n"
      "PROFILES consumed (graph-level — the node reasons over ALL sources' DT2 "
      "profiles, read tolerantly):\n\n"
      "- CSV crosswalk profile (captured DT2, prose-string value shapes)\n"
      "- IPEDS SQL profile (LIVE DT2 this run — status **"
      (:sql-profile-status r) "**)\n\n"
      "```clojure\n;; CSV crosswalk profile (consumed)\n"
      (with-out-str (pp/pprint (:csv-profile r)))
      "\n;; IPEDS SQL profile (consumed, live)\n"
      (with-out-str (pp/pprint (:sql-profile r)))
      "```\n\n"
      "---\n\n"
      "## A. DERIVED competency questions (for HITL review)\n\n"
      "node status: **" (:status d) "** · origin: **" (:origin d)
      "** · spec-recorded?: **" (:spec-recorded? d) "**\n\n"
      "The DERIVED CQs (VERBATIM — this is the HITL review surface):\n\n"
      (apply str
             (map-indexed
              (fn [i cq]
                (str (inc i) ". " cq "\n"
                     (when-let [r (get (:rationale d) i)]
                       (str "   - rationale: " r "\n"))))
              (:competency-questions d)))
      (when (:error d) (str "\nnode error: `" (:error d) "`\n"))
      "\n### Persisted as the ORSD spec build! reads (S14 projection read-back)\n\n"
      "`ontology/get-ontology-spec` for the derive ontology-id returns the spec "
      "whose `:competency-questions` are EXACTLY the derived CQs — this is the same "
      "projection build!'s S15 exit-criterion-stage reads:\n\n"
      "```clojure\n"
      (with-out-str (pp/pprint (:spec-after-derive r)))
      "```\n\n"
      "PROOF of persistence: spec :competency-questions == derived CQs? **"
      (= (vec (:competency-questions d))
         (vec (:competency-questions (:spec-after-derive r))))
      "**\n\n"
      "---\n\n"
      "## B. Consumer override (HITL seed/override)\n\n"
      "node status: **" (:status s) "** · origin: **" (:origin s)
      "** · spec-recorded?: **" (:spec-recorded? s) "**\n\n"
      "Consumer SUPPLIED these CQs (derivation SKIPPED):\n\n"
      (apply str (map-indexed (fn [i cq] (str (inc i) ". " cq "\n"))
                              (:competency-questions s)))
      "\nThe ORSD spec for the supplied ontology-id carries the SUPPLIED CQs "
      "(override took effect):\n\n```clojure\n"
      (with-out-str (pp/pprint (:spec-after-supplied r)))
      "```\n\n"
      "PROOF of override: spec :competency-questions == supplied CQs? **"
      (= (vec supplied-cqs)
         (vec (:competency-questions (:spec-after-supplied r))))
      "**\n\n"
      "---\n\n"
      "## Verdict (adversarial — to be confirmed by the HITL reviewer)\n\n"
      "GROUNDED: each derived CQ should be answerable from what the profiles show "
      "the sources contain (the crosswalk's CIP↔SOC linking + the IPEDS "
      "institution/program fields). A CQ about a thing no profile mentions would be "
      "an ungrounded defect — review for it.\n\n"
      "GOAL-ANCHORED (not self-fulfilling): the CQs should test the GOAL's core "
      "connection (program ↔ occupation, scoped to Louisiana) — NOT merely "
      "paraphrase the extraction. Review that the questions exercise the goal's "
      "intent, especially the CROSS-SOURCE link via the shared codes.\n\n"
      "HITL-REVIEWABLE + OVERRIDABLE: the derived CQs + per-CQ rationale are "
      "surfaced above (A); the supplied-override path (B) lets a reviewer replace "
      "them. Both persist as the S14 ORSD spec the S15 gate judges.\n\n"
      "DOMAIN-AGNOSTIC (discipline 12): the CQ prompt body names NO industry "
      "concept — verified by test `cq-prompt-is-domain-agnostic` rendering the "
      "prompt with a neutral goal. The only domain reference is the runtime goal.\n"))
    (println "Capture written:" capture-path)
    capture-path))

(defn -main [& _]
  (let [f (future (try {:ok (run-all! {})} (catch Throwable t {:err t})))
        res (deref f 290000 ::timeout)]
    (cond
      (= ::timeout res) (do (println "TIMEOUT — aborting") (System/exit 2))
      (:err res) (do (println "ERROR:" (.getMessage ^Throwable (:err res)))
                     (.printStackTrace ^Throwable (:err res))
                     (System/exit 1))
      :else (do (save-capture! (:ok res))
                (println "DERIVE STATUS:" (get-in res [:ok :derive :status]))
                (println "SUPPLIED STATUS:" (get-in res [:ok :supplied :status]))
                (System/exit 0)))))

(comment
  (require '[dt5-requirements-cq-node-live-verify :as dt5] :reload)
  (def r (dt5/run-all! {}))
  (dt5/save-capture! r))
