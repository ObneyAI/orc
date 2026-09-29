(ns ai.obney.orc.llm.core
  "Structured LLM prediction over SIO and litellm.router.

  SIO owns prompt rendering, response parsing, validation, multimodal message
  construction, and tool schemas. This namespace owns provider calls and ORC's
  typed streaming channel contract."
  (:require [clojure.core.async :as async :refer [<! >! chan close! go-loop]]
            [clojure.string :as str]
            [litellm.router :as router]
            [litellm.streaming :as streaming]
            [malli.core :as m]
            [malli.transform :as mt]
            [sio.core :as sio]))

(defn register-provider! [config-name provider-spec]
  (router/register! config-name provider-spec))

(defn quick-setup! []
  (router/quick-setup!))

(defn list-providers []
  (router/list-providers))

(defn- request-options [options]
  (cond-> (dissoc options
                  :validate?
                  :with-metadata?
                  :with-provider-evidence?
                  :use-function-calling?
                  :force-tool-choice?
                  :on-chunk
                  :debounce-ms
                  :timeout-ms)
    (:timeout-ms options) (assoc :timeout (:timeout-ms options))))

;; --------------------------------------------------------------------------- ;;
;; Optional means nullable
;; --------------------------------------------------------------------------- ;;
;; A provider that supports function calling renders every declared tool
;; property, whether or not the caller marked it :optional — there is no way
;; to ask a provider to omit a property. Measured against openai/gpt-5.6-luna
;; via OpenRouter: an optional field typed as a plain, non-nullable JSON type
;; gets FILLER instead ("N/A", "x", an invented date), because the model must
;; produce *something* structurally valid and nothing tells it that skipping
;; the value is allowed. Making every optional field (and every optional map
;; entry nested inside it, at any depth) admit `null` on the wire gives the
;; model an honest way to say "no value" — and it takes that option instead
;; of inventing one.
;;
;; `nullable-optionals` (wire) and `drop-null-optionals` (decode, just below
;; `validate-outputs`) are two mirror-image walks over the SAME schema shapes,
;; and they read their shape table from one place: `descend`. A schema type
;; `descend` does not recognize is a LEAF to both sides — wire leaves it
;; exactly as declared, decode never tries to walk a value against it. This
;; is deliberate: an earlier version had `nullable-optionals` fall through to
;; a generic catch-all that recursed into every vector-shaped form, while
;; `drop-null-optionals` only handled `:map`/`:vector`/`:maybe`/`:multi`
;; explicitly. That meant `:or`, `:and`, `:sequential`, `:set`, `:tuple`, and
;; `:map-of` got optional entries marked nullable on the wire with no decode
;; side able to turn a returned null back into absence — the provider was
;; told null was fine and then validation rejected it anyway, which is worse
;; than not offering nullability there at all. `descend` is now the one
;; definition of which shapes are walked, so a shape cannot gain wire
;; nullability without also gaining decode-side null-absence, or vice versa.
;;
;; `:or` and `:and` are deliberately absent from `descend`: choosing which
;; declared alternative applies from a decoded VALUE (decode) and rewriting
;; every alternative identically at the SCHEMA level (wire) are different
;; problems, and solving them independently is exactly the class of mistake
;; this table exists to prevent. Their entries are offered to the provider,
;; and validated, exactly as declared today — unchanged from before this fix.
;;
;; `wire-output` applies the same rule to a flattened top-level output field:
;; an :optional field's own declared spec is wrapped in `:maybe` too, unless
;; it already accepts nil.

(defn- accepts-nil?
  "True when a Malli schema form validates `nil` — either because it already
   declares nullability (`[:maybe ...]`, `:any`, ...), or because dropping a
   null value at that position would be a no-op anyway."
  [form]
  (try (m/validate form nil) (catch Exception _ false)))

(defn- split-schema-props
  "Split a composite form's tail into its optional Malli properties map (or
   nil) and its remaining entries/branches/children."
  [more]
  (if (map? (first more)) [(first more) (rest more)] [nil more]))

(defn- descend
  "The single definition of which Malli schema shapes `nullable-optionals`
   and `drop-null-optionals` walk INTO, and how. Returns a descriptor map, or
   nil for a leaf shape neither side descends into (see the note above).

   :map     — entries carry per-entry :optional, the only shape with entry-
              level optionality.
   :multi   — a dispatch key selects one branch's schema.
   :maybe   — unwrap to the single child.
   :vector/:sequential/:set — one child schema governs every element.
   :tuple   — one child schema per position.
   :map-of  — only the VALUE child is descended; keys are untouched."
  [form]
  (when (vector? form)
    (let [[schema-type & more] form
          [props children] (split-schema-props more)]
      (case schema-type
        :map
        {:kind :map
         :props props
         :entries (for [[entry-key & r] children]
                    (let [[entry-props s] (if (map? (first r))
                                            [(first r) (second r)]
                                            [nil (first r)])]
                      {:key entry-key :props entry-props :schema s}))}

        :multi
        {:kind :multi
         :props props
         :dispatch (:dispatch props)
         :branches (for [[branch-tag s] children]
                     {:tag branch-tag :schema s})}

        :maybe
        {:kind :single :props props :schema (first children)}

        (:vector :sequential :set)
        {:kind :homogeneous :type schema-type :props props :schema (first children)}

        :tuple
        {:kind :tuple :props props :schemas (vec children)}

        :map-of
        {:kind :map-of :props props :key-schema (first children) :value-schema (second children)}

        nil))))

(defn- nullable-optionals
  "Rewrite a Malli schema FORM so every OPTIONAL `:map` entry, at any depth
   `descend` walks into, admits `null`. Required entries are left exactly as
   declared. Operates on the schema form (a vector), before `sio` renders it
   as JSON Schema or prompt text."
  [form]
  (if-let [d (descend form)]
    (case (:kind d)
      :map
      (into (if (:props d) [:map (:props d)] [:map])
            (for [{:keys [key props schema]} (:entries d)]
              (let [s' (nullable-optionals schema)
                    s'' (if (and (:optional props) (not (accepts-nil? s'))) [:maybe s'] s')]
                (if props [key props s''] [key s'']))))

      :multi
      (into (if (:props d) [:multi (:props d)] [:multi])
            (for [{:keys [tag schema]} (:branches d)]
              [tag (nullable-optionals schema)]))

      :single
      (into (if (:props d) [:maybe (:props d)] [:maybe]) [(nullable-optionals (:schema d))])

      :homogeneous
      (into (if (:props d) [(:type d) (:props d)] [(:type d)]) [(nullable-optionals (:schema d))])

      :tuple
      (into (if (:props d) [:tuple (:props d)] [:tuple]) (map nullable-optionals (:schemas d)))

      :map-of
      (into (if (:props d) [:map-of (:props d)] [:map-of])
            [(:key-schema d) (nullable-optionals (:value-schema d))]))
    form))

(defn- wire-output
  "A flattened top-level output field, as it will be offered to the
   provider: an :optional field's own spec admits `null` too (unless it
   already does), and its nested optional entries follow
   `nullable-optionals`."
  [{:keys [spec optional] :as field}]
  (let [s (nullable-optionals spec)]
    (assoc field :spec (if (and optional (not (accepts-nil? s))) [:maybe s] s))))

(defn- wire-spec-outputs
  "The spec offered to the provider: every declared output run through
   `wire-output`. Inputs are untouched — only outputs carry the presence
   contract a provider needs to be told about."
  [spec]
  (update spec :outputs #(mapv wire-output %)))

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
  (let [prompt (str (sio/spec->prompt (wire-spec-outputs spec))
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
                    :tools [(sio/outputs->tool-definition (wire-spec-outputs spec))]}
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

(def ^:private provider-json-transformer
  (mt/transformer
   (mt/key-transformer {:decode keyword :encode name})
   (mt/json-transformer)))

(declare prepare-multi-dispatches)

(defn- map-entry-value [value key]
  (when (map? value)
    (cond
      (contains? value key) [key (get value key)]
      (and (keyword? key) (contains? value (name key)))
      [(name key) (get value (name key))])))

(defn- prepare-map-children [schema value]
  (if-not (map? value)
    value
    (reduce
     (fn [result [key _ child-schema]]
       (if-let [[actual-key child-value] (map-entry-value result key)]
         (assoc result actual-key
                (prepare-multi-dispatches child-schema child-value))
         result))
     value
     (m/children schema))))

(defn- matching-multi-branch [schema dispatch-value]
  (some (fn [[branch-value _ branch-schema]]
          (when (or (= branch-value dispatch-value)
                    (and (keyword? branch-value)
                         (string? dispatch-value)
                         (= (subs (str branch-value) 1) dispatch-value)))
            [branch-value branch-schema]))
        (m/children schema)))

(defn- prepare-multi [schema value]
  (let [dispatch (:dispatch (m/properties schema))]
    (if (and (keyword? dispatch) (map? value))
      (if-let [[actual-key dispatch-value] (map-entry-value value dispatch)]
        (if-let [[branch-value branch-schema]
                 (matching-multi-branch schema dispatch-value)]
          (prepare-multi-dispatches branch-schema
                                    (-> value
                                        (dissoc actual-key)
                                        (assoc dispatch branch-value)))
          value)
        value)
      value)))

(defn- prepare-multi-dispatches
  "Normalize keyword-valued :multi dispatches before Malli chooses a branch.
   Malli's ordinary JSON transformer handles the remaining schema-directed
   decoding, but branch selection necessarily happens before child decoders."
  [schema value]
  (let [schema (m/schema schema)
        children (m/children schema)]
    (case (m/type schema)
      :map (prepare-map-children schema value)
      :multi (prepare-multi schema value)
      (:vector :sequential :list :set)
      (if (sequential? value)
        (mapv #(prepare-multi-dispatches (first children) %) value)
        value)
      :tuple
      (if (sequential? value)
        (mapv (fn [child-schema child-value]
                (prepare-multi-dispatches child-schema child-value))
              children value)
        value)
      :map-of
      (if (map? value)
        (into (empty value)
              (map (fn [[key child-value]]
                     [key (prepare-multi-dispatches (second children)
                                                    child-value)]))
              value)
        value)
      (:or :and)
      (reduce (fn [result child-schema]
                (prepare-multi-dispatches child-schema result))
              value children)
      :orn
      (reduce (fn [result [_ _ child-schema]]
                (prepare-multi-dispatches child-schema result))
              value children)
      (:maybe :schema)
      (if-let [child-schema (first children)]
        (prepare-multi-dispatches child-schema value)
        value)
      value)))

(defn decode-provider-value
  "Decode one parsed provider JSON value through its declared Malli schema.
   Canonical JSON keyword spellings become keywords; string enums and numeric
   strings retain their declared semantics."
  [schema value]
  (m/decode schema
            (prepare-multi-dispatches schema value)
            provider-json-transformer))

(defn- drop-null-optionals
  "The decode mirror of `nullable-optionals`, reading the SAME `descend`
   table: walk `value` against its ORIGINAL declared schema `form` (never
   the wire-transformed one — decoding must check what the field actually
   promises) and remove an OPTIONAL entry whose value came back `null`, at
   any depth `descend` walks into, because that null is the provider's way
   of saying absent. An entry whose own schema already accepts nil keeps its
   null — that is the author's declared meaning, not a filler default.
   Required entries are never touched: a null there is left in place so
   `validate-outputs` still rejects it. A shape `descend` does not recognize
   is a leaf: the value passes through unwalked, exactly as `nullable-
   optionals` left its schema unrewritten."
  [form value]
  (cond
    (nil? value) nil
    :else
    (if-let [d (descend form)]
      (case (:kind d)
        :map
        (if-not (map? value)
          value
          (reduce (fn [v {:keys [key props schema]}]
                    (cond
                      (not (contains? v key)) v

                      (and (:optional props) (nil? (get v key)) (not (accepts-nil? schema)))
                      (dissoc v key)

                      :else
                      (update v key #(drop-null-optionals schema %))))
                  value (:entries d)))

        :multi
        (let [dispatch-key (:dispatch d)
              dispatch-value (when (and (keyword? dispatch-key) (map? value))
                                (get value dispatch-key))
              ;; The provider's raw JSON dispatch value is a string (there is
              ;; no JSON keyword), while a branch tag declared in the schema
              ;; form is typically a keyword — the same string/keyword
              ;; equivalence `matching-multi-branch` applies once decoding
              ;; has run. This walk happens BEFORE decoding, so it must
              ;; tolerate that mismatch itself to find the right branch.
              branch (some (fn [{:keys [tag schema]}]
                             (when (or (= tag dispatch-value)
                                       (and (keyword? tag)
                                            (string? dispatch-value)
                                            (= (subs (str tag) 1) dispatch-value)))
                               schema))
                           (:branches d))]
          (if branch (drop-null-optionals branch value) value))

        :single
        (drop-null-optionals (:schema d) value)

        :homogeneous
        (case (:type d)
          :set (if (set? value)
                 (into #{} (map #(drop-null-optionals (:schema d) %)) value)
                 value)
          (if (sequential? value)
            (mapv #(drop-null-optionals (:schema d) %) value)
            value))

        :tuple
        (if (and (sequential? value) (= (count (:schemas d)) (count value)))
          (mapv drop-null-optionals (:schemas d) value)
          value)

        :map-of
        (if (map? value)
          (into (empty value)
                (map (fn [[k v]] [k (drop-null-optionals (:value-schema d) v)]))
                value)
          value))
      value)))

(defn- drop-null-optional-outputs
  "Top-level mirror of `drop-null-optionals`: a flattened OPTIONAL output
   field that came back `null` is absent, unless its own declared spec
   already accepts nil. Runs against the ORIGINAL declared `fields` — never
   the wire-transformed spec — and always before `validate-outputs`, so a
   required field's null is untouched and still fails validation."
  [fields outputs]
  (reduce (fn [acc {:keys [name spec optional]}]
            (cond
              (not (contains? acc name)) acc

              (and optional (nil? (get acc name)) (not (accepts-nil? spec)))
              (dissoc acc name)

              :else
              (update acc name #(drop-null-optionals spec %))))
          outputs fields))

(defn- validate-outputs [fields outputs]
  ;; SIO's public collection validator intentionally validates only values that
  ;; are present. ORC's prediction boundary additionally requires every declared
  ;; output to be present unless the field is declared :optional, or its Malli
  ;; schema itself accepts nil.
  ;;
  ;; :optional is about PRESENCE, not about type. An optional field the provider
  ;; omitted is skipped, because reading its absent key as nil would fail a spec
  ;; that never agreed to accept nil. An optional field that IS present is still
  ;; validated exactly like a required one.
  (reduce
   (fn [decoded {:keys [name spec optional] :as field}]
     (if (and spec (or (contains? decoded name) (not optional)))
       (let [value (decode-provider-value spec (get decoded name))]
         (sio/validate-field field value)
         (assoc decoded name value))
       decoded))
   outputs
   fields))

(defn- provider-name [provider]
  (if (keyword? provider) (name provider) (str provider)))

(defn- diagnostic-string [value]
  (cond
    (nil? value) nil
    (keyword? value) (name value)
    :else (str value)))

(defn- response-tool-calls [response]
  (let [message (-> response :choices first :message)]
    (or (:tool-calls message) (:tool_calls message))))

(defn- provider-evidence
  "Return the deliberately small, provider-neutral diagnostic allowlist.
   Tool arguments and arbitrary response/provider metadata never enter it."
  [provider response]
  (let [choice (-> response :choices first)
        tool-calls (response-tool-calls response)
        finish-reason (or (:finish-reason choice) (:finish_reason choice))]
    {:provider (provider-name provider)
     :model (diagnostic-string (:model response))
     :response-id (diagnostic-string (:id response))
     :finish-reason (diagnostic-string finish-reason)
     :tool-call-present? (boolean (seq tool-calls))
     :tool-call-name (diagnostic-string
                      (or (get-in tool-calls [0 :function :name])
                          (get-in tool-calls [0 :name])))
     :usage (:usage response)
     :output-truncated? (contains? #{"length" :length "max_tokens" :max_tokens}
                                   finish-reason)}))

(defn- structured-failure [message failure-kind evidence & [cause]]
  (ex-info message
           {:failure-kind failure-kind
            :provider-evidence evidence}
           cause))

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
        response (try
                   (router/completion
                    provider
                    (if function-calling?
                      (function-request spec inputs options)
                      (marker-request spec inputs options)))
                   (catch Exception e
                     (throw (structured-failure (.getMessage e)
                                                :transport-failure
                                                {:provider (provider-name provider)}
                                                e))))
        evidence (provider-evidence provider response)
        ;; SIO consumes Clojure's kebab-case response vocabulary. Some provider
        ;; adapters expose the wire spelling instead; normalize only this known
        ;; structural key while retaining the original response for evidence.
        parse-response (let [message (-> response :choices first :message)]
                         (if (and (:tool_calls message)
                                  (not (:tool-calls message)))
                           (assoc-in response [:choices 0 :message :tool-calls]
                                     (:tool_calls message))
                           response))
        raw-response (-> response :choices first :message :content)
        parsed-result (cond
                 (and function-calling? (empty? (:choices response)))
                 (throw (structured-failure "Provider returned an empty structured response"
                                            :empty-provider-response evidence))

                 (and function-calling?
                      (:force-tool-choice? options)
                      (not (:tool-call-present? evidence)))
                 (throw (structured-failure "Forced tool choice returned no tool call"
                                            :missing-forced-tool-call evidence))

                 function-calling?
                 (try
                   (sio/parse-tool-call-response parse-response (:outputs spec))
                   (catch Exception e
                     (throw (structured-failure (.getMessage e)
                                                :tool-call-parsing-failed evidence e))))

                 :else
                 (sio/parse-output raw-response spec))
        parsed (if (and function-calling?
                        (:tool-call-present? evidence)
                        (seq (-> parse-response :choices first :message :tool-calls))
                        (nil? parsed-result))
                 (throw (structured-failure "Tool call arguments could not be decoded"
                                            :tool-call-parsing-failed evidence))
                 parsed-result)
        outputs (if validate?
                  (try
                    (validate-outputs (:outputs spec)
                                       (drop-null-optional-outputs (:outputs spec) parsed))
                    (catch Exception e
                      (throw (structured-failure (.getMessage e)
                                                 :schema-validation-failed evidence e))))
                  parsed)]
    (if (:with-metadata? options)
      (cond-> {:outputs outputs
               :usage (:usage response)
               :model (:model response)
               :raw-response raw-response}
        (:with-provider-evidence? options)
        (assoc :provider-evidence evidence))
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
                              (validate-outputs (:outputs spec)
                                                 (drop-null-optional-outputs (:outputs spec) parsed))
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
