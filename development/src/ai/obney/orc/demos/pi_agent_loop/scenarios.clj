(ns ai.obney.orc.demos.pi-agent-loop.scenarios
  "Deterministic and mandatory live scenarios for the Pi-loop demo."
  (:require [clojure.string :as str]
            [cheshire.core :as json]
            [ai.obney.orc.demos.pi-agent-loop.core :as loop]
            [ai.obney.orc.demos.pi-agent-loop.runtime :as runtime]
            [ai.obney.orc.demos.pi-agent-loop.model :as model]
            [ai.obney.orc.demos.pi-agent-loop.tools :as tools]
            [ai.obney.orc.demos.pi-agent-loop.events :as events]
            [ai.obney.orc.orc-service.interface :as orc]))

(def pinned-live-model "google/gemini-3.6-flash")
(def live-nonce "ORC-LIVE-NONCE-7F31")

(defn nonce-lookup [{:keys [inputs]}]
  {:answer (str live-nonce ":" (:query inputs))})

(defn primary-fails [_]
  (throw (ex-info "primary source unavailable" {:scenario :live-recovery})))

(defn backup-lookup [{:keys [inputs]}]
  {:answer (str live-nonce ":backup:" (:query inputs))})

(defn delegated-lookup [{:keys [inputs]}]
  {:answer (str live-nonce ":delegated-child:" (:query inputs))})

(defn retain-provider-evidence [{:keys [inputs]}]
  {:provider (:provider inputs)
   :model (:model inputs)
   :response-id (:response-id inputs)
   :usage-json (:usage-json inputs)})

(defn- fq [name]
  (str "ai.obney.orc.demos.pi-agent-loop.scenarios/" name))

(defn- lookup-workflow [name function-name]
  (orc/workflow name
    (orc/blackboard {:query :string :answer :string})
    (orc/code "lookup" :fn (fq function-name) :reads [:query] :writes [:answer])))

(defn- evidence-recorder [context scenario]
  (let [sheet-id (orc/build-workflow!
                  context
                  (orc/workflow (str "pi-demo-live-evidence-" scenario)
                    (orc/blackboard {:provider :string :model :string :response-id :string
                                     :usage-json :string})
                    (orc/code "retain-provider-evidence" :fn (fq "retain-provider-evidence")
                      :reads [:provider :model :response-id :usage-json]
                      :writes [:provider :model :response-id :usage-json])))
        ticks (atom [])]
    {:ticks ticks
     :record! (fn [provenance]
                (let [result (orc/execute context sheet-id
                                          {:provider (str (:provider provenance))
                                           :model (str (:model provenance))
                                           :response-id (str (or (:response-id provenance) "unavailable"))
                                           :usage-json (json/generate-string (:usage provenance))})]
                  (when-not (= :success (:status result))
                    (throw (ex-info "Could not durably retain provider evidence"
                                    {:scenario scenario :result result})))
                  (swap! ticks conj (:trace-id result))))}))

(defn deterministic [context]
  (let [sheet-id (orc/build-workflow! context (lookup-workflow "pi-demo-deterministic" "nonce-lookup"))
        correlation-id (random-uuid)
        tool (tools/orc-workflow-tool
              {:name "lookup" :description "Retrieve a fact"
               :context context :sheet-id sheet-id :correlation-id correlation-id
               :input-fn #(select-keys % [:query]) :content-fn #(get-in % [:outputs :answer])})
        turns (atom 0)
        result (loop/run
                {:prompts [{:role :user :content "Find the fact"}]
                 :tools {"lookup" tool}
                 :model-turn (fn [_ _]
                               (case (swap! turns inc)
                                 1 {:role :assistant :content "" :stop-reason :tool-use
                                    :tool-calls [{:id "demo-call" :name "lookup"
                                                  :arguments {:query "deterministic"}}]}
                                 2 {:role :assistant :content (str "Evidence: " live-nonce)
                                    :tool-calls [] :stop-reason :stop}))})]
    {:result result :evidence-class :deterministic-conformance
     :durable (events/durable-summary context result)}))

(defn live-tool-selection [context]
  (model/setup!)
  (let [sheet-id (orc/build-workflow! context (lookup-workflow "pi-demo-live-nonce" "nonce-lookup"))
        correlation-id (random-uuid) evidence (atom [])
        recorder (evidence-recorder context "tool-selection")
        tool (assoc (tools/orc-workflow-tool
                     {:name "lookup_secret" :description "Look up the secret result for a query."
                      :context context :sheet-id sheet-id :correlation-id correlation-id
                      :input-fn #(select-keys % [:query])
                      :content-fn #(get-in % [:outputs :answer])})
                    :parameters {:type "object" :properties {:query {:type "string"}}
                                 :required ["query"] :additionalProperties false})
        tools {"lookup_secret" tool}
        turn (model/live-model-turn
              {:provider :openrouter :model pinned-live-model :tools tools :evidence evidence
               :on-evidence (:record! recorder)
               :system-prompt (str "You are testing an agent loop. You must use the available tool "
                                   "to answer. Never invent tool results. After receiving the result, "
                                   "answer with that exact result.")})
        result (loop/run {:prompts [{:role :user
                                    :content "Retrieve the secret for query live-grounding."}]
                          :tools tools :model-turn turn})
        calls (filter #(= :tool-execution-start (:type %)) (:events result))
        final-text (:content (last (:new-messages result)))]
    (when-not (and (= 1 (count calls)) (str/includes? final-text live-nonce)
                   (every? #(and (:model %) (:usage %)) @evidence))
      (throw (ex-info "Live tool-selection evidence did not satisfy the contract"
                      {:calls calls :final-text final-text :evidence @evidence})))
    {:evidence-class :live-model :scenario :tool-selection
     :result result :provider-evidence @evidence
     :durable (assoc (events/durable-summary context result)
                     :model-evidence-ticks @(:ticks recorder))}))

(defn live-error-recovery [context]
  (model/setup!)
  (let [primary-id (orc/build-workflow! context (lookup-workflow "pi-demo-live-primary" "primary-fails"))
        backup-id (orc/build-workflow! context (lookup-workflow "pi-demo-live-backup" "backup-lookup"))
        correlation-id (random-uuid) evidence (atom [])
        recorder (evidence-recorder context "error-recovery")
        mk-tool (fn [name description sheet-id]
                  (assoc (tools/orc-workflow-tool
                          {:name name :description description :context context :sheet-id sheet-id
                           :correlation-id correlation-id :input-fn #(select-keys % [:query])
                           :content-fn #(get-in % [:outputs :answer])})
                         :parameters {:type "object" :properties {:query {:type "string"}}
                                      :required ["query"] :additionalProperties false}))
        tool-map {"primary_lookup" (mk-tool "primary_lookup" "Preferred fact source" primary-id)
                  "backup_lookup" (mk-tool "backup_lookup" "Fallback fact source" backup-id)}
        turn (model/live-model-turn
              {:provider :openrouter :model pinned-live-model :tools tool-map :evidence evidence
               :on-evidence (:record! recorder)
               :system-prompt (str "Use primary_lookup first. If its observed result is an error, "
                                   "recover by calling backup_lookup. Answer only from successful tool evidence.")})
        result (loop/run {:prompts [{:role :user :content "Find recovery-test using the sources."}]
                          :tools tool-map :model-turn turn})
        calls (mapv :tool-name (filter #(= :tool-execution-start (:type %)) (:events result)))
        final-text (:content (last (:new-messages result)))]
    (when-not (and (= ["primary_lookup" "backup_lookup"] calls)
                   (str/includes? final-text live-nonce))
      (throw (ex-info "Live recovery evidence did not satisfy the contract"
                      {:calls calls :final-text final-text :evidence @evidence})))
    {:evidence-class :live-model :scenario :tool-error-recovery
     :result result :provider-evidence @evidence
     :durable (assoc (events/durable-summary context result)
                     :model-evidence-ticks @(:ticks recorder))}))

(defn live-steering [context]
  (model/setup!)
  (let [sheet-id (orc/build-workflow! context (lookup-workflow "pi-demo-live-steering" "nonce-lookup"))
        correlation-id (random-uuid) evidence (atom [])
        recorder (evidence-recorder context "steering")
        selected (promise) release (promise)
        base-tool (assoc (tools/orc-workflow-tool
                          {:name "lookup_secret" :description "Retrieve the hidden secret."
                           :context context :sheet-id sheet-id :correlation-id correlation-id
                           :input-fn #(select-keys % [:query])
                           :content-fn #(get-in % [:outputs :answer])})
                         :parameters {:type "object" :properties {:query {:type "string"}}
                                      :required ["query"] :additionalProperties false})
        execute (:execute base-tool)
        held-tool (assoc base-tool :execute (fn [arguments update!]
                                              (deliver selected true)
                                              @release
                                              (execute arguments update!)))
        tool-map {"lookup_secret" held-tool}
        turn (model/live-model-turn
              {:provider :openrouter :model pinned-live-model :tools tool-map :evidence evidence
               :on-evidence (:record! recorder)
               :system-prompt (str "You must retrieve the hidden secret with lookup_secret. "
                                   "If a later user steering message specifies an output prefix, "
                                   "follow it exactly and then include the exact tool result.")})
        harness (runtime/create {:context context :tools tool-map :model-turn turn})
        running (future (runtime/prompt! harness [{:role :user
                                                   :content "Retrieve live-steering."}]))]
    (when-not (deref selected 60000 false)
      (runtime/abort! harness)
      (throw (ex-info "Live model did not select the tool before steering timeout" {})))
    (runtime/steer! harness [{:role :user :content "Prefix the final answer with STEERED:"}])
    (deliver release true)
    (let [result @running
          final-text (:content (last (:new-messages result)))]
      (when-not (and (str/starts-with? final-text "STEERED:")
                     (str/includes? final-text live-nonce))
        (throw (ex-info "Live model did not adapt to queued steering"
                        {:final-text final-text :evidence @evidence})))
      {:evidence-class :live-model :scenario :steering-adaptation
       :result result :provider-evidence @evidence
       :durable (assoc (events/durable-summary context result)
                       :model-evidence-ticks @(:ticks recorder))})))

(defn live-structured-orc [context]
  (model/setup!)
  (let [child-id (orc/build-workflow!
                  context
                  (orc/workflow "pi-demo-live-structured-child"
                    (orc/blackboard {:query :string :answer :string})
                    (orc/code "child-only-lookup" :fn (fq "delegated-lookup")
                      :reads [:query] :writes [:answer])))
        parent-id (orc/build-workflow!
                   context
                   (orc/workflow "pi-demo-live-structured-parent"
                     (orc/blackboard {:query :string :answer :string})
                     (orc/delegate "delegated-child" :target-sheet-id child-id
                       :reads [:query] :writes [:answer])))
        correlation-id (random-uuid) evidence (atom [])
        recorder (evidence-recorder context "structured-orc")
        tool (assoc (tools/orc-workflow-tool
                     {:name "run_delegated_lookup"
                      :description "Run the required structured delegated ORC workflow."
                      :context context :sheet-id parent-id :correlation-id correlation-id
                      :input-fn #(select-keys % [:query])
                      :content-fn #(get-in % [:outputs :answer])})
                    :parameters {:type "object" :properties {:query {:type "string"}}
                                 :required ["query"] :additionalProperties false})
        tool-map {"run_delegated_lookup" tool}
        turn (model/live-model-turn
              {:provider :openrouter :model pinned-live-model :tools tool-map :evidence evidence
               :on-evidence (:record! recorder)
               :system-prompt (str "Use run_delegated_lookup for this request. Its answer comes from "
                                   "a child workflow. Return the exact observed child answer.")})
        result (loop/run {:prompts [{:role :user
                                    :content "Run structured delegated work for live-structure."}]
                          :tools tool-map :model-turn turn})
        final-text (:content (last (:new-messages result)))
        correlated (events/correlation-events context correlation-id)
        started (filter #(= :sheet/tree-tick-started (:event/type %)) correlated)]
    (when-not (and (= 2 (count started))
                   (every? #(= correlation-id (:correlation-id %)) correlated)
                   (str/includes? final-text (str live-nonce ":delegated-child:")))
      (throw (ex-info "Live structured ORC evidence did not satisfy the contract"
                      {:started (count started) :final-text final-text
                       :evidence @evidence})))
    {:evidence-class :live-model :scenario :structured-orc-adaptation
     :result result :provider-evidence @evidence
     :durable (assoc (events/durable-summary context result)
                     :correlation-events correlated
                     :model-evidence-ticks @(:ticks recorder))}))

(defn live-streaming [context prompt]
  (model/setup!)
  (let [evidence (atom [])
        recorder (evidence-recorder context "provider-streaming")
        result (loop/run {:prompts [{:role :user :content prompt}]
                          :model-turn (model/live-streaming-text-turn
                                       {:provider :openrouter :model pinned-live-model
                                        :evidence evidence
                                        :on-evidence (:record! recorder)
                                        :system-prompt "Reply with one short sentence."})})
        updates (filter #(= :message-update (:type %)) (:events result))]
    (when-not (and (seq updates) (seq (:content (last (:new-messages result))))
                   (:model (first @evidence)) (:usage (first @evidence)))
      (throw (ex-info "Live streaming evidence did not satisfy the contract"
                      {:updates (count updates) :evidence @evidence})))
    {:evidence-class :live-model :scenario :provider-streaming
     :result result :provider-evidence @evidence
     :durable {:model-evidence-ticks @(:ticks recorder)}}))

(defn live-streaming-tool [context]
  (model/setup!)
  (let [sheet-id (orc/build-workflow!
                  context (lookup-workflow "pi-demo-live-streaming-tool" "nonce-lookup"))
        correlation-id (random-uuid) evidence (atom [])
        recorder (evidence-recorder context "provider-streaming-tool")
        tool (assoc (tools/orc-workflow-tool
                     {:name "streamed_lookup" :description "Retrieve the hidden streaming proof."
                      :context context :sheet-id sheet-id :correlation-id correlation-id
                      :input-fn #(select-keys % [:query])
                      :content-fn #(get-in % [:outputs :answer])})
                    :parameters {:type "object" :properties {:query {:type "string"}}
                                 :required ["query"] :additionalProperties false})
        tool-map {"streamed_lookup" tool}
        turn (model/live-streaming-model-turn
              {:provider :openrouter :model pinned-live-model :tools tool-map
               :evidence evidence :on-evidence (:record! recorder)
               :system-prompt (str "You must call streamed_lookup. After its result arrives, "
                                   "reply with the exact observed result.")})
        result (loop/run {:prompts [{:role :user
                                    :content "Retrieve streaming-grounding."}]
                          :tools tool-map :model-turn turn})
        calls (filter #(= :tool-execution-start (:type %)) (:events result))
        tool-deltas (filter #(and (= :message-update (:type %))
                                  (= :tool-call-delta
                                     (get-in % [:assistant-message-event :kind])))
                            (:events result))
        text-deltas (filter #(and (= :message-update (:type %))
                                  (= :text-delta
                                     (get-in % [:assistant-message-event :kind])))
                            (:events result))
        final-text (:content (last (:new-messages result)))]
    (when-not (and (= 1 (count calls)) (seq tool-deltas) (seq text-deltas)
                   (str/includes? final-text live-nonce)
                   (every? #(and (:model %) (:response-id %) (:usage %)) @evidence))
      (throw (ex-info "Live streamed tool evidence did not satisfy the contract"
                      {:calls (count calls) :tool-deltas (count tool-deltas)
                       :text-deltas (count text-deltas) :final-text final-text
                       :evidence @evidence})))
    {:evidence-class :live-model :scenario :provider-streamed-tool
     :result result :provider-evidence @evidence
     :durable (assoc (events/durable-summary context result)
                     :model-evidence-ticks @(:ticks recorder))}))
