(ns ai.obney.orc.demos.pi-agent-loop.model
  "Real provider adapter for the Pi-shaped transcript.

   Unlike deterministic response fixtures, calls in this namespace are the
   evidence boundary for model/provider capability."
  (:require [cheshire.core :as json]
            [clojure.core.async :as async]
            [litellm.router :as router]
            [litellm.streaming :as streaming]
            [litellm.providers.openrouter :as openrouter]
            [ai.obney.orc.orc-service.core.executor :as executor]))

(defn setup! [] (executor/setup-providers!))

(defn- provider-message [message]
  (case (:role message)
    :user {:role :user :content (:content message)}
    :assistant (or (:provider-message message)
                   {:role :assistant :content (:content message)})
    :tool-result {:role :tool
                  :tool-call-id (:tool-call-id message)
                  :content (if (string? (:content message))
                             (:content message) (json/generate-string (:content message)))}
    nil))

(defn- tool-definition [[name tool]]
  {:type "function"
   :function {:name name
              :description (or (:description tool) name)
              :parameters (or (:parameters tool)
                              {:type "object" :properties {} :additionalProperties false})}})

(defn- provider-messages [messages system-prompt]
  (let [messages (vec (keep provider-message messages))]
    (if system-prompt
      (into [{:role :system :content system-prompt}] messages)
      messages)))

(defn- retain-evidence! [evidence on-evidence provenance]
  (when evidence (swap! evidence conj provenance))
  (when on-evidence (on-evidence provenance))
  provenance)

(defn- parse-arguments [arguments]
  (cond
    (map? arguments) arguments
    (string? arguments) (json/parse-string arguments true)
    (nil? arguments) {}
    :else (throw (ex-info "Provider returned unsupported tool arguments"
                          {:arguments-type (type arguments)}))))

(defn- normalize-tool-call [index call]
  (let [function (:function call)]
    {:id (str (or (:id call) (str "provider-call-" index)))
     :name (str (or (:name function) (:name call)))
     :arguments (parse-arguments (or (:arguments function) (:arguments call)))
     :source-index index}))

(defn- finish-reason [choice tool-calls]
  (let [reason (or (:finish-reason choice) (:finish_reason choice))]
    (cond
      (contains? #{:length "length" :max_tokens "max_tokens"} reason) :length
      (seq tool-calls) :tool-use
      (= :error reason) :error
      :else :stop)))

(defn live-model-turn
  "Create a non-streaming real-provider turn function for tool-capable runs."
  [{:keys [provider model system-prompt tools request-options evidence on-evidence]
    :or {provider :openrouter request-options {}}}]
  (fn [messages turn-config]
    (let [request (merge {:messages (provider-messages messages system-prompt)
                          :tools (mapv tool-definition tools)}
                         (when model {:model model}) request-options turn-config)
          response (router/completion provider request)
          choice (-> response :choices first)
          message (:message choice)
          raw-calls (vec (or (:tool-calls message) (:tool_calls message) []))
          tool-calls (mapv normalize-tool-call (range) raw-calls)
          provenance {:provider (name provider) :model (or (:model response) model)
                      :response-id (:id response) :usage (:usage response)
                      :finish-reason (or (:finish-reason choice) (:finish_reason choice))}]
      (retain-evidence! evidence on-evidence provenance)
      {:role :assistant :content (or (:content message) "") :tool-calls tool-calls
       :stop-reason (finish-reason choice tool-calls)
       :provider-message message :provider-evidence provenance})))

(defn live-streaming-text-turn
  "Create a real-provider text-stream turn. Intended for the transport proof;
   tool-capable live scenarios use `live-model-turn` so calls are never lost."
  [{:keys [provider model system-prompt request-options evidence on-evidence]
    :or {provider :openrouter request-options {}}}]
  (fn [messages turn-config]
    (let [request (merge {:messages (provider-messages messages system-prompt)
                          :stream true :stream-options {:include-usage true}}
                         (when model {:model model}) request-options turn-config)
          channel (router/completion provider request)]
      (loop [content "" updates [] usage nil observed-model model response-id nil]
        (if-let [chunk (async/<!! channel)]
          (if (streaming/is-error-chunk? chunk)
            {:message {:role :assistant :content content :tool-calls [] :stop-reason :error
                       :provider-evidence {:provider (name provider) :model observed-model
                                           :response-id response-id :usage usage
                                           :stream-error chunk}}
             :updates updates}
            (let [delta (streaming/extract-content chunk)]
              (recur (str content (or delta ""))
                     (cond-> updates delta (conj {:kind :text-delta :delta delta}))
                     (or (:usage chunk) usage) (or (:model chunk) observed-model)
                     (or (:id chunk) response-id))))
          (let [provenance {:provider (name provider) :model observed-model
                            :response-id response-id :usage usage :streamed true}]
            (retain-evidence! evidence on-evidence provenance)
            {:initial-message {:role :assistant :content "" :tool-calls [] :stop-reason nil}
             :updates updates
             :message {:role :assistant :content content :tool-calls [] :stop-reason :stop
                       :provider-evidence provenance}}))))))

(defn- preserve-openrouter-stream-chunk
  "Preserve OpenAI-compatible tool deltas that litellm-clj 0570e94 currently
   drops while retaining its normal normalized chunk shape."
  [_provider-name chunk]
  (let [choice (first (:choices chunk))
        delta (:delta choice)
        tool-calls (mapv (fn [call]
                           {:index (:index call)
                            :id (:id call)
                            :type (:type call)
                            :function {:name (get-in call [:function :name])
                                       :arguments (get-in call [:function :arguments])}})
                         (:tool_calls delta))]
    (cond-> {:id (:id chunk) :object (:object chunk) :created (:created chunk)
             :model (:model chunk)
             :choices [{:index (:index choice)
                        :delta (cond-> {:role (some-> (:role delta) keyword)
                                       :content (:content delta)}
                                 (seq tool-calls) (assoc :tool-calls tool-calls))
                        :finish-reason (some-> (:finish_reason choice) keyword)}]}
      (:usage chunk) (assoc :usage (openrouter/transform-usage (:usage chunk))))))

(defn- append-tool-fragment [calls fragment]
  (let [index (or (:index fragment) 0)]
    (update calls index
            (fn [current]
              (let [current (or current {:source-index index :arguments-json ""})]
                (cond-> current
                  (:id fragment) (assoc :id (:id fragment))
                  (:type fragment) (assoc :type (:type fragment))
                  (get-in fragment [:function :name])
                  (assoc :name (get-in fragment [:function :name]))
                  (get-in fragment [:function :arguments])
                  (update :arguments-json str (get-in fragment [:function :arguments]))))))))

(defn- finalize-streamed-calls [calls]
  (->> calls
       (sort-by key)
       (mapv (fn [[index call]]
               {:id (str (or (:id call) (str "provider-call-" index)))
                :name (str (:name call))
                :arguments (parse-arguments (:arguments-json call))
                :source-index index}))))

(defn live-streaming-model-turn
  "Create a provider-native streaming turn that reconstructs both text and
   fragmented tool calls. The OpenRouter transformer override is deliberately
   scoped to stream consumption and can be removed once the pinned litellm-clj
   dependency preserves `delta.tool_calls` itself."
  [{:keys [provider model system-prompt tools request-options evidence on-evidence]
    :or {provider :openrouter request-options {}}}]
  (fn [messages turn-config]
    (let [request (merge {:messages (provider-messages messages system-prompt)
                          :tools (mapv tool-definition tools)
                          :stream true :stream-options {:include-usage true}}
                         (when model {:model model}) request-options turn-config)
          consume
          (fn []
            (let [channel (router/completion provider request)]
              (loop [content "" updates [] calls {} usage nil observed-model model
                     response-id nil observed-finish nil]
                (if-let [chunk (async/<!! channel)]
                  (if (streaming/is-error-chunk? chunk)
                    (let [provenance {:provider (name provider) :model observed-model
                                      :response-id response-id :usage usage
                                      :stream-error chunk}]
                      (retain-evidence! evidence on-evidence provenance)
                      {:message {:role :assistant :content content :tool-calls []
                                 :stop-reason :error :provider-evidence provenance}
                       :updates updates})
                    (let [delta (get-in chunk [:choices 0 :delta])
                          text (:content delta)
                          fragments (vec (:tool-calls delta))]
                      (recur (str content (or text ""))
                             (cond-> updates
                               text (conj {:kind :text-delta :delta text})
                               (seq fragments)
                               (into (mapv #(hash-map :kind :tool-call-delta
                                                      :delta %) fragments)))
                             (reduce append-tool-fragment calls fragments)
                             (or (:usage chunk) usage)
                             (or (:model chunk) observed-model)
                             (or (:id chunk) response-id)
                             (or (get-in chunk [:choices 0 :finish-reason])
                                 observed-finish))))
                  (let [tool-calls (finalize-streamed-calls calls)
                        provenance {:provider (name provider) :model observed-model
                                    :response-id response-id :usage usage :streamed true
                                    :finish-reason observed-finish}
                        provider-calls (mapv (fn [{:keys [id name arguments]}]
                                               {:id id :type "function"
                                                :function {:name name
                                                           :arguments (json/generate-string arguments)}})
                                             tool-calls)
                        provider-message (cond-> {:role :assistant :content content}
                                           (seq provider-calls)
                                           (assoc :tool-calls provider-calls))]
                    (retain-evidence! evidence on-evidence provenance)
                    {:initial-message {:role :assistant :content "" :tool-calls []
                                       :stop-reason nil}
                     :updates updates
                     :message {:role :assistant :content content :tool-calls tool-calls
                               :stop-reason (finish-reason
                                             {:finish-reason observed-finish} tool-calls)
                               :provider-message provider-message
                               :provider-evidence provenance}})))))]
      (if (= :openrouter provider)
        (with-redefs [openrouter/transform-streaming-chunk-impl
                      preserve-openrouter-stream-chunk]
          (consume))
        (consume)))))
