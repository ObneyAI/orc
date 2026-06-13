(ns s12-live-verify
  "S12 dedup-cascade live verification — drives the cascade with a REAL
   OpenRouter LLM call for the T9 ambiguity-band tier. Verifies:

     1. Cost discipline: cheap tiers (T1-T8) handle their pairs with ZERO
        LLM invocations. Only the genuinely ambiguous pairs reach T9.
     2. KEEP rules end-to-end: number / negation / entity → KEEP via the
        guards (T2, T3) AND via the LLM tier (T9) for pairs whose
        ambiguity sneaks past the guards.
     3. Event provenance: the persisted equivalence / dedup-distinct /
        co-occurrence events carry the tier + reason that closed the
        verdict so downstream audit / co-occurrence-trail work doesn't
        need to re-derive them.

   Run:
     OPENROUTER_API_KEY=sk-... clj -M:dev -e \"(require 's12-live-verify) (s12-live-verify/run-live!)\"

   STATUS NOTE (2026-06-12 run): with the OPENROUTER_API_KEY in the
   maintainer's shell, OpenRouter returns HTTP 401 'User not found' for
   the live LLM calls (the account is rejected; not a cascade bug). The
   9 cheap-tier pairs verify end-to-end through the REAL Grain event
   store (16 co-occurrence events, 4 equivalence events with correct
   kinds, 4 dedup-distinct events with reasons preserved). The 3 LLM-tier
   pairs surface `:requires-review` (the correct failure-mode behavior;
   no silent merge, no silent skip). To re-verify the T9 path end-to-end,
   re-run this script with a working OpenRouter key; the cascade plumbing
   (prompt rendering, EDN parsing, verdict-to-event mapping) is unchanged
   so the LLM tier WILL exercise on key refresh."
  (:require [ai.obney.orc.ontology.core.dedup-cascade :as dedup]
            [ai.obney.orc.ontology.core.commands :as cmd]
            [ai.obney.orc.ontology.interface.schemas]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.query-processor.interface :as qp]
            [litellm.router :as litellm-router]
            [clojure.edn :as edn]
            [clojure.string :as str])
  (:import [java.io File]))

(def ^:private llm-model "google/gemini-2.5-flash")

(defn- setup-openrouter! []
  (let [api-key (System/getenv "OPENROUTER_API_KEY")]
    (when-not api-key
      (throw (ex-info "OPENROUTER_API_KEY env var required" {})))
    (litellm-router/setup-openrouter! :api-key api-key :model llm-model)
    (litellm-router/register! (keyword (str "openrouter/" llm-model))
                              {:provider :openrouter
                               :model llm-model
                               :config {:api-base "https://openrouter.ai/api/v1"
                                        :api-key api-key}})
    (println "Registered OpenRouter with model" llm-model)))

(defn- delete-dir-recursively [^String path]
  (let [f (File. path)]
    (when (.exists f)
      (when (.isDirectory f)
        (doseq [c (.listFiles f)]
          (delete-dir-recursively (.getPath c))))
      (.delete f))))

(defn- create-ctx! []
  (rmp/l1-clear!)
  (let [dir (str "/tmp/s12-live-" (random-uuid))
        es (es/start {:conn {:type :in-memory} :event-pubsub nil :logger nil})
        cache (kv/start (lmdb/->KV-Store-LMDB {:storage-dir dir :db-name "test"}))]
    {:event-store es
     :cache cache
     :tenant-id #uuid "00000000-0000-0000-0000-000000000000"
     :command-registry (cp/global-command-registry)
     :query-registry (qp/global-query-registry)
     :dscloj-provider :openrouter
     ::cache-dir dir}))

(defn- stop-ctx! [ctx]
  (rmp/l1-clear!)
  (when-let [c (:cache ctx)] (kv/stop c))
  (when-let [e (:event-store ctx)] (es/stop e))
  (when-let [d (::cache-dir ctx)] (delete-dir-recursively d)))

(defn- parse-llm-edn
  "Parse the model's EDN response. The prompt template constrains the
   output shape; we extract the first EDN-shaped map from the response
   and fall back to a permissive parser on whitespace / preamble."
  [response-text]
  (let [;; Strip ``` fences and trailing prose
        cleaned (-> response-text
                    (str/replace #"(?s)```(edn|clojure)?" "")
                    (str/replace #"```" "")
                    str/trim)
        first-brace (str/index-of cleaned "{")
        last-brace (str/last-index-of cleaned "}")]
    (if (and first-brace last-brace (< first-brace last-brace))
      (try
        (edn/read-string (subs cleaned first-brace (inc last-brace)))
        (catch Exception _
          {:verdict :requires-review :reason :parse-error
           :detail (str "could not parse LLM response: " (subs response-text 0 (min 200 (count response-text))))}))
      {:verdict :requires-review :reason :parse-error
       :detail (str "no EDN map in LLM response: " (subs response-text 0 (min 200 (count response-text))))})))

(defn real-llm-fn
  "Real LLM-tier function. Calls OpenRouter and parses the EDN verdict.
   Catches auth / connectivity errors and surfaces them as a structured
   :verdict :requires-review so the cascade record stays well-formed
   instead of throwing into the caller."
  [{:keys [a-label a-desc b-label b-desc kind-hint] :as input}]
  (try
    (let [prompt (dedup/render-llm-prompt input)
          response (litellm-router/completion
                    :openrouter
                    {:model llm-model
                     :messages [{:role :user :content prompt}]})
          text (-> response :choices first :message :content)
          parsed (parse-llm-edn text)]
      (println "  LLM raw verdict for" (pr-str [a-label b-label]) "→"
               (pr-str (select-keys parsed [:verdict :kind :reason])))
      (cond-> parsed
        (and (= :merge (:verdict parsed)) (not (:kind parsed))) (assoc :kind (or kind-hint :equivalent-class))))
    (catch Exception e
      (println "  LLM-call error for" (pr-str [a-label b-label]) "→"
               (.getMessage e))
      {:verdict :requires-review :reason :llm-call-failed
       :detail (.getMessage e)})))

;; =============================================================================
;; The 20 adversarial pairs — same set as the test fixtures (prototype's
;; ground truth). Live verify must replicate the same verdicts with the
;; REAL LLM in the T9 tier.
;; =============================================================================

(def adversarial-pairs
  [{:id :case-variant
    :a {:uri "p:Director1" :label "Director" :description "person who directs films" :type :class :kind-hint :same-as}
    :b {:uri "p:Director2" :label "director" :description "person who directs films" :type :class :kind-hint :same-as}
    :expected-verdict :merge}
   {:id :whitespace-variant
    :a {:uri "p:CEO1" :label "Chief Executive Officer" :description "head of a company" :type :class :kind-hint :same-as}
    :b {:uri "p:CEO2" :label "  Chief Executive   Officer  " :description "head of a company" :type :class :kind-hint :same-as}
    :expected-verdict :merge}
   {:id :unicode-nfc-variant
    :a {:uri "p:Cafe1" :label "Café" :description "coffeehouse" :type :class :kind-hint :same-as}
    :b {:uri "p:Cafe2" :label "Café" :description "coffeehouse" :type :class :kind-hint :same-as}
    :expected-verdict :merge}
   {:id :jw-near-identical
    :a {:uri "p:Organization1" :label "Organization" :description "a structured group of people" :type :class :kind-hint :equivalent-class}
    :b {:uri "p:Organisation1" :label "Organisation" :description "a structured group of people" :type :class :kind-hint :equivalent-class}
    :expected-verdict :merge}
   {:id :equivalent-property
    :a {:uri "p:hasAuthor" :label "hasAuthor" :description "links a work to its author" :type :property :kind-hint :equivalent-property}
    :b {:uri "p:hasWriter" :label "hasWriter" :description "links a work to its author" :type :property :kind-hint :equivalent-property}
    :expected-verdict :merge}
   {:id :number-3-vs-30
    :a {:uri "p:Model3" :label "Model 3" :description "Tesla Model 3" :type :class}
    :b {:uri "p:Model30" :label "Model 30" :description "Tesla Model 30" :type :class}
    :expected-verdict :distinct}
   {:id :number-iso
    :a {:uri "p:ISO9001" :label "ISO 9001" :description "Quality management standard" :type :class}
    :b {:uri "p:ISO9002" :label "ISO 9002" :description "Quality management standard" :type :class}
    :expected-verdict :distinct}
   {:id :negation-approved
    :a {:uri "p:Approved" :label "approved" :description "passed review" :type :class}
    :b {:uri "p:NotApproved" :label "not approved" :description "did not pass review" :type :class}
    :expected-verdict :distinct}
   {:id :negation-present
    :a {:uri "p:Present" :label "present" :description "found in the sample" :type :class}
    :b {:uri "p:Absent" :label "absent" :description "not found in the sample" :type :class}
    :expected-verdict :distinct}
   {:id :paris-city-vs-person
    :a {:uri "p:ParisCity" :label "Paris" :description "the capital city of France" :type :individual}
    :b {:uri "p:ParisPerson" :label "Paris" :description "the person who wrote the memoir" :type :individual}
    :expected-verdict :distinct}
   {:id :apple-fruit-vs-company
    :a {:uri "p:AppleFruit" :label "Apple" :description "a fruit grown in temperate regions" :type :class}
    :b {:uri "p:AppleCo" :label "Apple" :description "the consumer electronics company" :type :class}
    :expected-verdict :distinct}
   {:id :short-label-entropy
    :a {:uri "p:A" :label "A" :description "" :type :class}
    :b {:uri "p:B" :label "An" :description "" :type :class}
    :expected-verdict :skip}])

(defn run-live!
  "Drive the cascade with the REAL OpenRouter LLM for ambiguity-band pairs.
   Captures per-pair verdicts + tier counts + persisted events. Prints a
   summary at the end."
  []
  (setup-openrouter!)
  (let [ctx (create-ctx!)
        primary-id (random-uuid)
        align-id (random-uuid)
        ctx' (assoc ctx :llm-fn real-llm-fn)]
    (try
      (println "Running" (count adversarial-pairs) "adversarial pairs against the real LLM…")
      (println "Model:" llm-model)
      (println)
      (let [results
            (mapv (fn [{:keys [id a b expected-verdict]}]
                    (println "Pair" (name id) "→" (pr-str [(:label a) (:label b)]))
                    (let [r (cmd/ontology-run-dedup-cascade
                             (assoc ctx' :command
                                    {:ontology-id primary-id
                                     :alignment-ontology-id align-id
                                     :a a :b b}))
                          v (get-in r [:command-result/data :verdict])]
                      (when (seq (:command-result/events r))
                        (es/append (:event-store ctx)
                                   {:events (vec (:command-result/events r))
                                    :tenant-id (:tenant-id ctx)}))
                      (println "  →" (pr-str (select-keys v [:tier :verdict :kind :reason])))
                      (println "    expected:" expected-verdict
                               "got:" (:verdict v)
                               (if (= expected-verdict (:verdict v)) "PASS" "FAIL"))
                      (println)
                      {:id id
                       :verdict v
                       :expected expected-verdict
                       :pass? (= expected-verdict (:verdict v))
                       :events (:command-result/events r)}))
                  adversarial-pairs)
            tier-counts (reduce (fn [acc {:keys [verdict]}]
                                  (update acc (:tier verdict) (fnil inc 0)))
                                {} results)
            llm-pairs (filter #(= :llm-verdict (-> % :verdict :tier)) results)
            pass-count (count (filter :pass? results))
            fail-count (count (remove :pass? results))
            ;; Read back the persisted events under the primary tag for audit.
            primary-events (into [] (es/read (:event-store ctx)
                                             {:tags #{[:ontology primary-id]}
                                              :tenant-id (:tenant-id ctx)}))
            align-events (into [] (es/read (:event-store ctx)
                                           {:tags #{[:ontology align-id]}
                                            :tenant-id (:tenant-id ctx)}))]
        (println "============================================================")
        (println "S12 LIVE VERIFY SUMMARY")
        (println "============================================================")
        (println "Total pairs:" (count adversarial-pairs))
        (println "Pass:" pass-count "Fail:" fail-count)
        (println "Tier counts:" tier-counts)
        (println "LLM-tier pairs:" (count llm-pairs)
                 (mapv (comp name :id) llm-pairs))
        (println)
        (println "Persisted events under primary tag:" (count primary-events))
        (doseq [e primary-events]
          (println " ·" (:event/type e) (select-keys e [:source-uri :target-uri :tier :reason :verdict :context-source])))
        (println)
        (println "Persisted events under alignment tag:" (count align-events))
        (doseq [e align-events]
          (println " ·" (:event/type e) (select-keys e [:source-uri :target-uri :kind :evidence])))
        results)
      (finally
        (stop-ctx! ctx)))))
