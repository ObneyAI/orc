(ns ai.obney.orc.orc-service.real-llm-ontology-builder-e2e-test
  "Gated live-OpenRouter coverage for DET-E2E-115 and DET-E2E-116.

  These tests deliberately use the public command path. They do not redefine,
  fake, replay, or script any extractor/model behavior."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.set]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.orc.ontology.interface :as ontology]
            [ai.obney.orc.ontology.interface.evolutionary :as evolutionary]
            [ai.obney.orc.ontology.interface.schemas]
            [ai.obney.orc.ontology.core.evolutionary-commands]
            [ai.obney.orc.orc-service.complex-e2e-support :as live]
            [ai.obney.orc.orc-service.test-helpers :as h]))

(defn- event-type [event]
  (or (:event/type event) (:type event)))

(defn- event-body [event]
  (or (:body event) event))

(defn- anomaly? [result]
  (contains? result :cognitect.anomalies/category))

(defn- evolutionary-events [ctx]
  (filterv #(= "evolutionary" (namespace (event-type %)))
           (live/events ctx)))

(defn- events-of [ctx type]
  (filterv #(= type (event-type %)) (live/events ctx)))

(defn- assert-all-model-calls-pinned! [ctx]
  (let [calls (live/model-completions ctx)]
    (is (seq calls) "ontology build must have executed real model-backed leaves")
    (doseq [call calls]
      (live/assert-pinned-model! call)
      (is (map? (:usage call))))
    calls))

(defn- build-config [ontology-id]
  {:ontology-id ontology-id
   :model live/openrouter-model
   :base-uri (str "https://example.test/ontology/" ontology-id "/")
   :similarity-threshold 0.82
   :enable-colbert? false
   :enable-embeddings? false})

(deftest det-e2e-115-real-llm-multi-source-build-duplicate-and-failure-recovery
  (testing "real heterogeneous extraction deduplicates input and publishes only complete snapshots"
    (live/with-real-openrouter
      (h/with-async-test-context [ctx]
        (live/register-openrouter!)
        (let [ctx (assoc ctx :dscloj-provider :openrouter)
              ontology-id (random-uuid)
              shared-text (str "A quasar routing policy sends urgent messages through a "
                               "priority channel. A normal channel handles routine messages.")
              sources [{:type "text" :content shared-text}
                       {:type "text" :content shared-text}
                       {:type "csv"
                        :content (str "name,purpose\n"
                                      "priority channel,urgent messages\n"
                                      "archive channel,retention\n")}]
              result (evolutionary/build-from-sources
                      ctx {:sources sources :config (build-config ontology-id)})
              registrations (events-of ctx :evolutionary/source-registered)
              extractions (events-of ctx :evolutionary/concepts-extracted)
              completions (events-of ctx :evolutionary/build-completed)]
          (is (not (anomaly? result)) (pr-str result))
          (is (= ontology-id (:ontology-id result)))
          (is (= 2 (count registrations))
              "one exact duplicate must produce one registered identity")
          (is (= 2 (count extractions))
              "one effective extraction per unique source")
          (is (= 1 (count completions)))
          (is (every? (comp seq :concepts event-body) extractions))
          (doseq [extraction extractions
                  :let [provenance (:model-provenance (event-body extraction))]]
            (is (= [live/openrouter-model] (:models provenance)))
            (is (uuid? (:trace-id provenance)))
            (is (seq (:calls provenance)))
            (is (every? #(pos? (get-in % [:usage :total-tokens] 0))
                        (:calls provenance))))
          (is (= (set (map (comp :source-id event-body) registrations))
                 (set (map (comp :source-id event-body) extractions))))
          (assert-all-model-calls-pinned! ctx)
          (is (:valid? (ontology/validate-turtle (:ttl-output result))))
          (let [mappings (evolutionary/get-all-canonical-mappings ctx)
                concepts-by-uri (->> extractions
                                     (mapcat (comp :concepts event-body))
                                     (map (juxt :uri identity))
                                     (into {}))
                source-sets (->> mappings
                                 (group-by val)
                                 vals
                                 (map (fn [entries]
                                        (set (keep #(get-in concepts-by-uri [(key %) :source-id])
                                                   entries)))))]
            (is (some #(= 2 (count %)) source-sets)
                (str "At least one overlap must map both contributing sources to one canonical identity; concepts="
                     (pr-str (mapv #(select-keys % [:label :alt-labels :entity-type :source-id])
                                   (vals concepts-by-uri))))))

          ;; Contact the real OpenRouter endpoint with a deliberately invalid
          ;; credential. This is an actual provider/HTTP failure, not a fake or
          ;; scripted model. No partial ontology snapshot may become current.
          (let [_ (live/register-openrouter-auth-failure!)
                before-terminal-count (count (events-of ctx :evolutionary/build-completed))
                before-snapshot-count (count (events-of ctx :evolutionary/ttl-snapshot-created))
                failed (try
                         (evolutionary/build-from-sources
                          ctx
                          {:sources [{:type "text"
                                      :content "A valid recovery precursor requiring extraction."}]
                           :config (build-config ontology-id)})
                         (catch Throwable t t))]
            (is (or (instance? Throwable failed)
                    (nil? (:build-id failed)))
                "induced extraction failure must be visible to the caller")
            (is (= before-terminal-count
                   (count (events-of ctx :evolutionary/build-completed))))
            (is (= before-snapshot-count
                   (count (events-of ctx :evolutionary/ttl-snapshot-created)))
                "failed build must publish no current snapshot"))

          (let [registrations-before-retry (count (events-of ctx :evolutionary/source-registered))
                retry-source "A valid recovery precursor requiring extraction."
                _ (live/register-openrouter!)
                retry (evolutionary/evolve
                       ctx {:ontology-id ontology-id
                            :sources [{:type "text" :content retry-source}]
                            :config (build-config ontology-id)})
                repeated (evolutionary/evolve
                           ctx {:ontology-id ontology-id
                                :sources [{:type "text" :content retry-source}]
                                :config (build-config ontology-id)})]
            (is (uuid? (:build-id retry)))
            (is (= (inc registrations-before-retry)
                   (count (events-of ctx :evolutionary/source-registered)))
                "Atomic failure stored no source; recovery registers it exactly once")
            (is (= 1 (count (filter #(= (:build-id retry) (:build-id (event-body %)))
                                    (events-of ctx :evolutionary/build-completed))))
                "Retry has exactly one terminal completion")
            (is (:valid? (ontology/validate-turtle (:ttl-output retry))))
            (is (= 0 (:new-sources-processed repeated)))
            (is (= (:ttl-output retry) (:ttl-output repeated))
                "Repeat export over unchanged sources must be byte-stable")))))))

(deftest det-e2e-116-real-llm-incremental-build-preserves-canonical-identity
  (testing "real incremental extraction retains baseline provenance and resolves renamed overlap"
    (live/with-real-openrouter
      (h/with-async-test-context [ctx]
        (live/register-openrouter!)
        (let [ctx (assoc ctx :dscloj-provider :openrouter)
              ontology-id (random-uuid)
              config (build-config ontology-id)
              baseline (evolutionary/build-from-sources
                        ctx {:sources [{:type "text"
                                       :content (str "The exact canonical concept label is Sentinel Queue. "
                                                     "Sentinel Queue is a durable priority queue that routes "
                                                     "urgent work before routine work.")}]
                             :config config})
              baseline-extraction (last (events-of ctx :evolutionary/concepts-extracted))
              baseline-concepts (:concepts (event-body baseline-extraction))
              baseline-source (:source-id (event-body baseline-extraction))
              incremental (evolutionary/evolve
                           ctx {:ontology-id ontology-id
                                :sources [{:type "text"
                                           :content (str "The existing canonical concept Sentinel Queue is now "
                                                         "also called Sentinel Work Queue. Preserve Sentinel Queue "
                                                         "as an exact label or alternate label. "
                                                         "Aurora Escalator is the only genuinely new named concept. "
                                                         "Extract exactly these two named concepts.")}]
                                :config config})
              all-extractions (events-of ctx :evolutionary/concepts-extracted)
              incremental-extraction (last all-extractions)
              incremental-concepts (:concepts (event-body incremental-extraction))
              baseline-uris (set (map :uri baseline-concepts))
              incremental-uris (set (map :uri incremental-concepts))
              shared-uris (clojure.set/intersection baseline-uris incremental-uris)
              new-uris (clojure.set/difference incremental-uris baseline-uris)
              incremental-source (:source-id (event-body incremental-extraction))
              resolution (last (events-of ctx :evolutionary/entities-resolved))
              snapshots (events-of ctx :evolutionary/ttl-snapshot-created)]
          (is (not (anomaly? baseline)) (pr-str baseline))
          (is (not (anomaly? incremental)) (pr-str incremental))
          (is (uuid? (:build-id baseline)))
          (is (uuid? (:build-id incremental)))
          (is (= 1 (:new-sources-processed incremental)))
          (is (not= baseline-source incremental-source))
          (is (= 2 (count all-extractions)))
          (is (seq baseline-concepts))
          (is (seq incremental-concepts))
          (is (= ontology-id (:ontology-id (event-body resolution))))
          (is (seq shared-uris)
              (str "renamed overlapping concept must resolve to baseline identity; baseline="
                   (pr-str (mapv #(select-keys % [:label :alt-labels :entity-type])
                                 baseline-concepts))
                   "; incremental="
                   (pr-str (mapv #(select-keys % [:label :alt-labels :entity-type])
                                 incremental-concepts))))
          (is (= 1 (count new-uris))
              (str "exactly one new canonical concept expected; new URIs="
                   (pr-str new-uris)))
          (is (= #{"Aurora Escalator"}
                 (set (map :label (filter #(contains? new-uris (:uri %))
                                         incremental-concepts)))))
          (is (= 2 (count snapshots)))
          (is (:valid? (ontology/validate-turtle (:ttl-output incremental))))
          (is (= (:ttl-output incremental)
                 (:ttl-string (event-body (last snapshots))))
              "public result and durable snapshot must be byte-identical")
          (is (= #{baseline-source incremental-source}
                 (set (map (comp :source-id event-body) all-extractions)))
              "both source mappings remain queryable")
          (assert-all-model-calls-pinned! ctx))))))
