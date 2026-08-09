(ns ai.obney.orc.llm.core
  "Structured LLM prediction over SIO and litellm.router.

  SIO owns prompt rendering, response parsing, validation, multimodal message
  construction, and tool schemas. This namespace owns provider calls and ORC's
  typed streaming channel contract."
  (:require [clojure.core.async :as async :refer [<! >! chan close! go-loop]]
            [clojure.string :as str]
            [litellm.router :as router]
            [litellm.streaming :as streaming]
            [sio.core :as sio]))

(defn register-provider! [config-name provider-spec]
  (router/register! config-name provider-spec))

(defn quick-setup! []
  (router/quick-setup!))

(defn list-providers []
  (router/list-providers))

(defn- request-options [options]
  (dissoc options
          :validate?
          :with-metadata?
          :use-function-calling?
          :force-tool-choice?
          :on-chunk
          :debounce-ms))

(defn- input-section [spec inputs marker?]
  (let [image-names (set (map :name (filter #(= :image (:type %)) (:inputs spec))))]
    (str/join
     "\n\n"
     (for [{:keys [name]} (:inputs spec)
           :when (not (image-names name))]
       (str (when marker? (str "[[ ## " (clojure.core/name name) " ## ]]\n"))
            (when-not marker? (str (clojure.core/name name) ": "))
            (get inputs name ""))))))

(defn- marker-request [spec inputs options]
  (let [prompt (str (sio/spec->prompt spec)
                    "\n\n"
                    (input-section spec inputs true))]
    (merge {:messages [{:role :user
                        :content (sio/build-message-content spec prompt inputs)}]}
           (request-options options))))

(defn- function-request [spec inputs options]
  (let [prompt (str (when-let [instructions (:instructions spec)]
                      (str instructions "\n\n"))
                    "Given the following inputs:\n"
                    (input-section spec inputs false)
                    "\n\nCall the submit_response function with your answer.")]
    (merge (cond-> {:messages [{:role :user
                                :content (sio/build-message-content spec prompt inputs)}]
                    :tools [(sio/outputs->tool-definition spec)]}
             ;; OPT-IN, default OFF. See below — forcing this is not safe for every
             ;; tool schema, and every workload built on ORC to date has run without it.
             (:force-tool-choice? options)
             (assoc :tool-choice {:type "function"
                                  :function {:name "submit_response"}}))
           (request-options options))))

;; --------------------------------------------------------------------------- ;;
;; Why the forced tool call is opt-in
;; --------------------------------------------------------------------------- ;;
;; This request previously carried :tool_choice (underscore) while litellm's provider
;; layer reads (:tool-choice request) (hyphen). Different Clojure keywords, so the key
;; never matched and tool_choice never reached the wire: the forced submit_response
;; call has been advisory for the entire life of the library.
;;
;; Correcting the key to :tool-choice makes the force real — and that BREAKS real
;; workloads. Measured against the BRYC recommendation engine on
;; google/gemini-3-flash-preview via OpenRouter, identical student and code, the only
;; difference being this key:
;;
;;   forced      -> :failure, "LLM output unparseable for keys [:preference-weights]
;;                   — no raw response text was returned by the provider" (8/8 students,
;;                   32/40 node executions)
;;   not forced  -> :success, preference-weights returned correctly
;;
;; The trigger is a `[:map-of ...]` output schema, which renders as
;; `additionalProperties` in the tool schema. Consumers use :map-of deliberately —
;; ORC's own executor does NOT flatten it, unlike [:map ...] — so "just use an explicit
;; map" changes their output contract rather than being a free swap.
;;
;; Default OFF therefore preserves the only behaviour any consumer has ever run on.
;; Callers whose tool schemas are free of additionalProperties can opt in per node with
;; {:force-tool-choice? true}.

(defn- validate-outputs [fields outputs]
  ;; SIO's public collection validator intentionally validates only values that
  ;; are present. ORC's prediction boundary additionally requires every
  ;; declared output to be present unless its Malli schema itself accepts nil.
  (doseq [{:keys [name spec] :as field} fields
          :when spec]
    (sio/validate-field field (get outputs name)))
  outputs)

(defn predict
  "Perform one structured provider invocation.

  Returns parsed outputs directly, or when :with-metadata? is true returns
  {:outputs :usage :model :raw-response}. Provider and validation failures are
  allowed to propagate so the caller owns retries and budget accounting."
  [provider spec inputs & [options]]
  (let [options (or options {})
        validate? (get options :validate? true)
        inputs (if validate? (sio/validate-inputs (:inputs spec) inputs) inputs)
        function-calling?
        (if (contains? options :use-function-calling?)
          (:use-function-calling? options)
          (try
            (router/supports-function-calling? provider)
            (catch Exception _ false)))
        response (router/completion
                  provider
                  (if function-calling?
                    (function-request spec inputs options)
                    (marker-request spec inputs options)))
        raw-response (-> response :choices first :message :content)
        parsed (if function-calling?
                 (sio/parse-tool-call-response response (:outputs spec))
                 (sio/parse-output raw-response spec))
        outputs (if validate?
                  (validate-outputs (:outputs spec) parsed)
                  parsed)]
    (if (:with-metadata? options)
      {:outputs outputs
       :usage (:usage response)
       :model (:model response)
       :raw-response raw-response}
      outputs)))

(defn- accumulate-stream-usage [acc usage]
  (reduce-kv (fn [result key value]
               (if (some? value) (assoc result key value) result))
             (or acc {})
             usage))

(defn- finalize-stream-usage [{:keys [prompt-tokens completion-tokens] :as usage}]
  (if (and prompt-tokens completion-tokens)
    (assoc usage :total-tokens (+ prompt-tokens completion-tokens))
    usage))

(defn- error-event [error]
  {:orc/event :error
   :error (if (instance? Throwable error)
            {:message (.getMessage ^Throwable error)
             :class (str (class error))}
            error)})

(defn predict-stream-v2
  "Return a core.async channel of typed ORC events.

  The channel emits :delta and debounced :fields events, then exactly one
  :final or :error terminal event before closing."
  [provider spec inputs & [options]]
  (let [options (or options {})
        validate? (get options :validate? false)
        output-ch (chan)]
    (try
      (let [inputs (if validate? (sio/validate-inputs (:inputs spec) inputs) inputs)
            stream-ch (router/completion
                       provider
                       (assoc (marker-request spec inputs options) :stream true))
            debounce-ms (get options :debounce-ms 50)
            accumulated (atom "")
            usage (atom nil)
            model (atom nil)
            last-fields-at (atom 0)]
        (go-loop []
          (if-let [chunk (<! stream-ch)]
            (if (streaming/is-error-chunk? chunk)
              (do
                (>! output-ch (error-event (dissoc chunk :type)))
                (close! stream-ch)
                (close! output-ch))
              (do
                (when-let [chunk-usage (:usage chunk)]
                  (swap! usage accumulate-stream-usage chunk-usage))
                (when-let [chunk-model (:model chunk)]
                  (reset! model chunk-model))
                (when-let [delta (streaming/extract-content chunk)]
                  (swap! accumulated str delta)
                  (>! output-ch {:orc/event :delta :text delta})
                  (let [now (System/currentTimeMillis)]
                    (when (>= (- now @last-fields-at) debounce-ms)
                      (let [fields (sio/parse-streaming-output @accumulated spec)]
                        (when (and (seq fields) (some some? (vals fields)))
                          (>! output-ch {:orc/event :fields :fields fields}))
                        (reset! last-fields-at now)))))
                (recur)))
            (try
              (let [parsed (sio/parse-streaming-output @accumulated spec)
                    outputs (if validate?
                              (validate-outputs (:outputs spec) parsed)
                              parsed)]
                (>! output-ch {:orc/event :final
                               :outputs outputs
                               :usage (some-> @usage finalize-stream-usage)
                               :model @model
                               :raw-response @accumulated}))
              (catch Throwable error
                (>! output-ch (error-event error)))
              (finally
                (close! output-ch))))))
      (catch Throwable error
        (async/put! output-ch (error-event error)
                    (fn [_] (close! output-ch)))))
    output-ch))
