(ns ai.obney.orc.llm.core-test
  (:require [clojure.core.async :as async]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hato.client :as http]
            [litellm.router :as router]
            [litellm.providers.openrouter :as openrouter]
            [malli.core :as m]
            [ai.obney.orc.llm.core :as core]
            [ai.obney.orc.llm.interface :as llm]))

(def qa
  {:inputs [{:name :question :spec :string :description "The question"}]
   :outputs [{:name :answer :spec :string :description "The answer"}]
   :instructions "Answer concisely."})

(defn- fake-stream [chunks]
  (let [ch (async/chan (max 1 (count chunks)))]
    (async/onto-chan! ch chunks)
    ch))

(defn- drain [ch]
  (loop [events []]
    (if-some [event (async/<!! ch)]
      (recur (conj events event))
      events)))

(deftest public-boundary-exposes-only-the-structured-prediction-contract
  (is (= '#{predict predict-stream-v2 decode-provider-value
            register-provider! quick-setup! list-providers}
         (set (keys (ns-publics 'ai.obney.orc.llm.interface))))))

(def ^:private nested-keyword-output
  {:inputs []
   :outputs
   [{:name :decision
     :spec
     [:map
      [:action [:enum :invoke]]
      [:request [:map
                 [:action [:= :beliefs]]
                 [:scope [:enum :current :all]]]]]}]})

(def ^:private raw-nested-keyword-output
  {:action "invoke"
   :request {:action "beliefs" :scope "current"}})

(def ^:private canonical-nested-keyword-output
  {:action :invoke
   :request {:action :beliefs :scope :current}})

(deftest validated-provider-outputs-are-schema-decoded-before-validation
  (testing "function-calling output returns canonical values"
    (with-redefs
      [router/supports-function-calling? (constantly true)
       router/completion
       (fn [& _]
         {:choices
          [{:message
            {:tool-calls
             [{:function
               {:name "submit_response"
                :arguments
                "{\"decision\":{\"action\":\"invoke\",\"request\":{\"action\":\"beliefs\",\"scope\":\"current\"}}}"}}]}}]})]
      (is (= {:decision canonical-nested-keyword-output}
             (llm/predict :test nested-keyword-output {}
                          {:validate? true :use-function-calling? true})))))

  (testing "marker output follows the same normalization contract"
    (with-redefs
      [router/supports-function-calling? (constantly false)
       router/completion
       (fn [& _]
         {:choices
          [{:message
            {:content
             (str "[[ ## decision ## ]]\n"
                  "{\"action\":\"invoke\",\"request\":"
                  "{\"action\":\"beliefs\",\"scope\":\"current\"}}")}}]})]
      (is (= {:decision canonical-nested-keyword-output}
             (llm/predict :test nested-keyword-output {}
                          {:validate? true :use-function-calling? false})))))

  (testing "validation disabled preserves the raw parsed provider representation"
    (with-redefs
      [router/supports-function-calling? (constantly true)
       router/completion
       (fn [& _]
         {:choices
          [{:message
            {:tool-calls
             [{:function
               {:name "submit_response"
                :arguments
                "{\"decision\":{\"action\":\"invoke\",\"request\":{\"action\":\"beliefs\",\"scope\":\"current\"}}}"}}]}}]})]
      (is (= {:decision raw-nested-keyword-output}
             (llm/predict :test nested-keyword-output {}
                          {:validate? false :use-function-calling? true}))))))

(deftest provider-management-is-a-transparent-boundary
  (let [registered (atom nil)]
    (with-redefs [router/register! (fn [name config] (reset! registered [name config]) :registered)
                  router/quick-setup! (constantly :configured)
                  router/list-providers (constantly [:openrouter :anthropic])]
      (is (= :registered (llm/register-provider! :test {:provider :openrouter})))
      (is (= [:test {:provider :openrouter}] @registered))
      (is (= :configured (llm/quick-setup!)))
      (is (= [:openrouter :anthropic] (llm/list-providers))))))

(deftest blocking-marker-prediction-preserves-metadata
  (let [raw "[[ ## answer ## ]]\nParis"
        calls (atom [])]
    (with-redefs [router/supports-function-calling? (constantly false)
                  router/completion (fn [provider request]
                                      (swap! calls conj [provider request])
                                      {:choices [{:message {:content raw}}]
                                       :usage {:prompt-tokens 5 :completion-tokens 2 :total-tokens 7}
                                       :model "test-model"})]
      (let [result (llm/predict :test qa {:question "Capital?"}
                                {:validate? false :with-metadata? true :temperature 0})]
        (is (= {:answer "Paris"} (:outputs result)))
        (is (= "test-model" (:model result)))
        (is (= raw (:raw-response result)))
        (is (not (contains? result :provider-evidence))
            "existing successful metadata shape remains unchanged")
        (is (= 1 (count @calls)))
        (is (= 0 (get-in @calls [0 1 :temperature])))))))

(deftest blocking-prediction-preserves-provider-request-controls
  (let [captured-request (atom nil)]
    (with-redefs [router/supports-function-calling? (constantly false)
                  router/completion (fn [_provider request]
                                      (reset! captured-request request)
                                      {:choices [{:message {:content "[[ ## answer ## ]]\nParis"}}]})]
      (is (= {:answer "Paris"}
             (llm/predict :openrouter qa {:question "Capital?"}
                          {:validate? false
                           :reasoning-effort :none
                           :max-tokens 512
                           :timeout-ms 42000})))
      (is (= :none (:reasoning-effort @captured-request)))
      (is (= 512 (:max-tokens @captured-request)))
      (is (= 42000 (:timeout @captured-request))
          "ORC's millisecond deadline reaches LiteLLM's provider timeout key")
      (is (not (contains? @captured-request :timeout-ms))
          "the ORC-only spelling does not leak into the provider request"))))

(deftest blocking-timeout-reaches-the-pinned-openrouter-http-transport
  (let [config-name (keyword (str "rr9-openrouter-timeout-" (random-uuid)))
        captured-http (atom nil)]
    (router/register!
     config-name
     {:provider :openrouter
      :model "test/model"
      :config {:api-key "not-sent"
               :api-base "https://transport.invalid"}})
    (try
      (with-redefs
        [http/post
         (fn [url options]
           (reset! captured-http {:url url :options options})
           (future
             {:status 200
              :body {:id "rr9-response"
                     :model "test/model"
                     :choices [{:index 0
                                :message {:role "assistant"
                                          :content "[[ ## answer ## ]]\nParis"}
                                :finish_reason "stop"}]
                     :usage {:prompt_tokens 1
                             :completion_tokens 1
                             :total_tokens 2}}}))]
        (is (= {:answer "Paris"}
               (llm/predict config-name qa {:question "Capital?"}
                            {:validate? false
                             :use-function-calling? false
                             :timeout-ms 42000})))
        (is (= "https://transport.invalid/chat/completions"
               (:url @captured-http)))
        (is (= 42000 (get-in @captured-http [:options :timeout]))
            "the request timeout reaches Hato, not only LiteLLM's router map")
        (is (true? (get-in @captured-http [:options :async?]))))
      (finally
        (router/unregister! config-name)))))

(deftest function-calling-performs-one-provider-invocation
  (let [calls (atom 0)]
    (with-redefs [router/supports-function-calling? (constantly true)
                  router/completion
                  (fn [_ request]
                    (swap! calls inc)
                    ;; Forcing is OPT-IN and this call does not opt in, so no
                    ;; tool-choice should be sent at all.
                    (is (nil? (:tool-choice request)))
                    (is (nil? (:tool_choice request)))
                    {:choices [{:message {:tool-calls
                                          [{:function {:name "submit_response"
                                                       :arguments "{\"answer\":\"Paris\"}"}}]}}]})]
      (is (= {:answer "Paris"}
             (llm/predict :test qa {:question "Capital?"} {:validate? false})))
      (is (= 1 @calls)))))

(deftest function-calling-failure-is-not-hidden-by-an-adapter-retry
  (let [calls (atom 0)]
    (with-redefs [router/supports-function-calling? (constantly true)
                  router/completion (fn [& _]
                                      (swap! calls inc)
                                      (throw (ex-info "provider failed" {})))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"provider failed"
                            (llm/predict :test qa {:question "Capital?"})))
      (is (= 1 @calls)))))

(deftest streaming-emits-ordered-orc-events-and-one-terminal
  (let [chunks [{:choices [{:delta {:content "[[ ## answer ## ]]\n"}}]}
                {:choices [{:delta {:content "Par"}}]}
                {:choices [{:delta {:content "is"}}]}
                {:choices [{:delta {}}]
                 :usage {:prompt-tokens 10 :completion-tokens 2}
                 :model "stream-model"}]]
    (with-redefs [router/completion (fn [_ request]
                                     (is (true? (:stream request)))
                                     (fake-stream chunks))]
      (let [events (drain (llm/predict-stream-v2 :test qa {:question "Capital?"}
                                                  {:debounce-ms 0}))
            terminals (filter #(#{:final :error} (:orc/event %)) events)
            final (last events)]
        (is (= ["[[ ## answer ## ]]\n" "Par" "is"]
               (mapv :text (filter #(= :delta (:orc/event %)) events))))
        (is (= 1 (count terminals)))
        (is (= :final (:orc/event final)))
        (is (= {:answer "Paris"} (:outputs final)))
        (is (= {:prompt-tokens 10 :completion-tokens 2 :total-tokens 12} (:usage final)))
        (is (= "stream-model" (:model final)))
        (is (= "[[ ## answer ## ]]\nParis" (:raw-response final)))
        (is (every? #(contains? % :orc/event) events))))))

(deftest streaming-provider-and-validation-failures-are-terminal-errors
  (testing "provider error"
    (with-redefs [router/completion
                  (fn [& _] (fake-stream [{:type :error :message "unavailable"}]))]
      (let [events (drain (llm/predict-stream-v2 :test qa {:question "Capital?"}))]
        (is (= [:error] (mapv :orc/event events)))
        (is (= "unavailable" (get-in events [0 :error :message]))))))
  (testing "requested output validation"
    (with-redefs [router/completion
                  (fn [& _] (fake-stream [{:choices [{:delta {}}]}]))]
      (let [events (drain (llm/predict-stream-v2 :test qa {:question "Capital?"}
                                                  {:validate? true}))]
        (is (= :error (:orc/event (last events))))
        (is (not-any? #(= :final (:orc/event %)) events))))))

(deftest validated-streaming-output-uses-provider-schema-decoding
  (let [content (str "[[ ## decision ## ]]\n"
                     "{\"action\":\"invoke\",\"request\":"
                     "{\"action\":\"beliefs\",\"scope\":\"current\"}}")]
    (with-redefs [router/completion
                  (fn [& _]
                    (fake-stream [{:choices [{:delta {:content content}}]}]))]
      (let [events (drain (llm/predict-stream-v2
                           :test nested-keyword-output {}
                           {:validate? true :debounce-ms 0}))
            final (last events)]
        (is (= :final (:orc/event final)))
        (is (= {:decision canonical-nested-keyword-output}
               (:outputs final)))))))

;; ===========================================================================
;; The forced tool call must survive the PROVIDER transform, not merely appear
;; in the map we hand to litellm.
;;
;; `function-request` emitted :tool_choice (underscore) while litellm's
;; OpenRouter provider reads (:tool-choice request) (hyphen). Those are
;; different Clojure keywords, so the key never matched and tool_choice never
;; reached the wire — the forced submit_response call was not actually forced,
;; leaving the model free to answer with prose. When it does, there is no tool
;; call, `parse-tool-call-response` yields nil, and the node fails.
;;
;; A test that stubs router/completion cannot catch this: it asserts what we
;; SEND to litellm, and the key is dropped one layer further in. This asserts
;; against litellm's real transform, which is where the wire request is built.
;; ===========================================================================

(deftest forced-tool-choice-is-opt-in-and-reaches-the-wire-when-requested
  (letfn [(capture [options]
            (let [captured (atom nil)]
              (with-redefs [router/supports-function-calling? (constantly true)
                            router/completion (fn [_provider request]
                                                (reset! captured request)
                                                {:choices [{:message {:tool_calls [{:function {:name "submit_response"
                                                                                               :arguments "{\"answer\":\"Paris\"}"}}]}}]})]
                (llm/predict :openrouter qa {:question "Capital?"} (merge {:validate? false} options)))
              @captured))]

    (testing "DEFAULT: no forced tool choice — the only behaviour consumers have run on"
      (let [req (capture {})]
        (is (nil? (:tool-choice req)))
        (is (nil? (:tool_choice (openrouter/transform-request-impl :openrouter req {})))
            "forcing by default breaks :map-of tool schemas on gemini via OpenRouter
             — see the note in llm/core.clj")))

    (testing "OPT-IN: {:force-tool-choice? true} reaches the wire under the key litellm reads"
      (let [req (capture {:force-tool-choice? true})]
        (is (= {:type "function" :function {:name "submit_response"}} (:tool-choice req))
            "must be :tool-choice — litellm reads the kebab key; an underscore is
             silently dropped")
        (is (= {:type "function" :function {:name "submit_response"}}
               (:tool_choice (openrouter/transform-request-impl :openrouter req {})))
            "and it must survive the provider transform, which is where the wire
             request is actually built")))

    (testing "the opt-in flag itself is never forwarded to the provider"
      (let [req (capture {:force-tool-choice? true})]
        (is (nil? (:force-tool-choice? req)))))))

(deftest structured-provider-failures-preserve-sanitized-evidence
  (let [response {:id "resp-123"
                  :model "test-model"
                  :usage {:prompt-tokens 8 :completion-tokens 3 :total-tokens 11}
                  :choices [{:finish-reason "length"
                             :message {:content nil}}]}]
    (with-redefs [router/supports-function-calling? (constantly true)
                  router/completion (fn [& _] response)]
      (let [failure (try
                      (llm/predict :openrouter qa {:question "Capital?"}
                                   {:force-tool-choice? true :with-metadata? true})
                      (catch clojure.lang.ExceptionInfo e e))
            data (ex-data failure)]
        (is (= :missing-forced-tool-call (:failure-kind data)))
        (is (= {:provider "openrouter"
                :model "test-model"
                :response-id "resp-123"
                :finish-reason "length"
                :tool-call-present? false
                :tool-call-name nil
                :usage {:prompt-tokens 8 :completion-tokens 3 :total-tokens 11}
                :output-truncated? true}
               (:provider-evidence data)))
        (is (not (contains? (:provider-evidence data) :choices)))))))

(deftest malformed-tool-arguments-are-distinct-from-missing-tool-call
  (with-redefs [router/supports-function-calling? (constantly true)
                router/completion
                (fn [& _]
                  {:id "resp-malformed"
                   :model "test-model"
                   :choices [{:finish_reason "tool_calls"
                              :message {:tool-calls
                                        [{:function {:name "submit_response"
                                                     :arguments "{not-json"}}]}}]})]
    (let [failure (try
                    (llm/predict :openrouter qa {:question "Capital?"}
                                 {:force-tool-choice? true :with-metadata? true})
                    (catch clojure.lang.ExceptionInfo e e))]
      (is (= :tool-call-parsing-failed (:failure-kind (ex-data failure))))
      (is (= true (get-in (ex-data failure) [:provider-evidence :tool-call-present?])))
      (is (= "submit_response"
             (get-in (ex-data failure) [:provider-evidence :tool-call-name]))))))

(deftest transport-failure-is-explicit-and-carries-no-provider-payload
  (with-redefs [router/supports-function-calling? (constantly true)
                router/completion (fn [& _] (throw (ex-info "upstream unavailable" {:secret "no"})))]
    (let [failure (try
                    (llm/predict :openrouter qa {:question "Capital?"}
                                 {:force-tool-choice? true :with-metadata? true})
                    (catch clojure.lang.ExceptionInfo e e))]
      (is (= "upstream unavailable" (.getMessage failure)))
      (is (= :transport-failure (:failure-kind (ex-data failure))))
      (is (= {:provider "openrouter"} (:provider-evidence (ex-data failure)))))))

(deftest empty-structured-response-is-distinct
  (with-redefs [router/supports-function-calling? (constantly true)
                router/completion (fn [& _] {:id "resp-empty" :choices []})]
    (let [failure (try
                    (llm/predict :openrouter qa {:question "Capital?"}
                                 {:force-tool-choice? true :with-metadata? true})
                    (catch clojure.lang.ExceptionInfo e e))]
      (is (= :empty-provider-response (:failure-kind (ex-data failure))))
      (is (= "resp-empty"
             (get-in (ex-data failure) [:provider-evidence :response-id]))))))

;; ---------------------------------------------------------------------------
;; SP-2 — ORC's own prediction-boundary validator knows what :optional means
;;
;; ORC does not use SIO's collection validator; `llm.core/validate-outputs` is
;; private and stricter, because ORC's boundary wants every declared output to
;; actually arrive. That strictness reads an ABSENT key as nil, which is the
;; right answer for a required field and the wrong answer for an optional one.
;; ---------------------------------------------------------------------------

(def ^:private partial-reply
  {:inputs [{:name :question :spec :string}]
   :outputs [{:name :answer :spec :string}
             {:name :aside :spec :string :optional true}]})

(deftest an-absent-optional-output-is-not-a-validation-failure
  (with-redefs [router/supports-function-calling? (constantly false)
                router/completion (fn [& _]
                                    {:choices [{:message {:content "[[ ## answer ## ]]\nParis"}}]})]
    (testing "the provider omitted the optional field entirely"
      (is (= {:answer "Paris"}
             (llm/predict :test partial-reply {:question "Capital?"} {:validate? true}))
          "an absent :optional key is an allowed shape, not a nil that fails :string"))))

(deftest an-absent-required-output-still-fails-validation
  (with-redefs [router/supports-function-calling? (constantly false)
                router/completion (fn [& _]
                                    {:choices [{:message {:content "[[ ## aside ## ]]\nby the way"}}]})]
    (testing "optional-awareness must not relax the required fields around it"
      (let [failure (try
                      (llm/predict :test partial-reply {:question "Capital?"} {:validate? true})
                      (catch clojure.lang.ExceptionInfo e e))]
        (is (instance? clojure.lang.ExceptionInfo failure))
        (is (= :schema-validation-failed (:failure-kind (ex-data failure))))))))

(deftest a-present-optional-output-is-still-validated
  (with-redefs [router/supports-function-calling? (constantly false)
                router/completion
                (fn [& _]
                  {:choices [{:message {:content "[[ ## answer ## ]]\nParis\n\n[[ ## aside ## ]]\nnoted"}}]})]
    (testing "present means checked — :optional is about presence, not about type"
      (is (= {:answer "Paris" :aside "noted"}
             (llm/predict :test partial-reply {:question "Capital?"} {:validate? true}))))))

;; ---------------------------------------------------------------------------
;; SP-2 — three parser behaviours that the sio pin move CHANGES
;;
;; Nothing in ORC exercised SIO's parser before these: every executor test stubs
;; `llm/predict` itself, so the parser only ever ran in production. Each case
;; below is a measured difference between sio 91e7d100 and sio d6d27f9, pinned
;; here so a later pin move cannot change it again unobserved.
;; ---------------------------------------------------------------------------

(deftest a-nil-provider-response-yields-nil-fields-not-a-bare-npe
  ;; At 91e7d100 this threw java.lang.NullPointerException straight out of the
  ;; parser: "Cannot invoke String.length() because this.text is null". A raw
  ;; NPE carries no :failure-kind, so ORC's structured-failure vocabulary and
  ;; every caller that classifies on it were blind to the case.
  (with-redefs [router/supports-function-calling? (constantly false)
                router/completion (fn [& _] {:choices [{:message {:content nil}}]})]
    (testing "the parser reports absence as data"
      (is (= {:answer nil}
             (llm/predict :test qa {:question "Capital?"} {:validate? false}))))

    (testing "and ORC's boundary then classifies it, rather than leaking an NPE"
      (let [failure (try
                      (llm/predict :test qa {:question "Capital?"} {:validate? true})
                      (catch Throwable t t))]
        (is (instance? clojure.lang.ExceptionInfo failure)
            "a bare NullPointerException here would be the old behaviour returning")
        (is (= :schema-validation-failed (:failure-kind (ex-data failure))))))))

(def ^:private tagged
  {:inputs [{:name :question :spec :string}]
   :outputs [{:name :tags :spec [:vector :string]}]})

(deftest a-scalar-under-a-vector-schema-is-lifted-into-a-one-element-vector
  ;; At 91e7d100 this parsed as {:tags "\"a\""} — a bare string under a
  ;; [:vector :string] schema, so validation rejected it and the node failed.
  ;; It now parses as a one-element vector, which VALIDATES. Nodes that used to
  ;; fail here will start succeeding; that is a widening, and it is deliberate.
  (with-redefs [router/supports-function-calling? (constantly false)
                router/completion (fn [& _]
                                    {:choices [{:message {:content "[[ ## tags ## ]]\n\"a\""}}]})]
    (is (= {:tags ["\"a\""]}
           (llm/predict :test tagged {:question "Tag it"} {:validate? false}))
        "the element keeps its literal quote characters; only the shape is lifted")
    (is (= {:tags ["\"a\""]}
           (llm/predict :test tagged {:question "Tag it"} {:validate? true}))
        "and the lifted shape passes the vector schema that the scalar failed")))

(def ^:private verdict-spec
  {:inputs [{:name :question :spec :string}]
   :outputs [{:name :verdict :spec :string}]})

;; ---------------------------------------------------------------------------
;; OptionalOutputPresence — optional means the provider may say "no value"
;;
;; specs/llm.allium, @invariant OptionalOutputPresence: every declared optional
;; output, and every optional entry of a map at any depth (nested maps, vector
;; items, union branches), is offered to the provider as nullable, so a
;; provider that emits every declared property is never forced to invent a
;; value. A null returned for such an optional field is absence — removed
;; before validation, never a present null — at every depth, for blocking and
;; streaming predictions alike. Required outputs/entries are never made
;; nullable; a null or missing value there still fails. An optional field
;; whose own declared schema already accepts null keeps a returned null.
;; ---------------------------------------------------------------------------

(def ^:private answer+optional-aside
  {:inputs []
   :outputs [{:name :answer :spec :string}
             {:name :aside :spec :string :optional true}]})

(defn- tool-properties [request]
  (get-in request [:tools 0 :function :parameters :properties]))

(defn- tool-required [request]
  (set (get-in request [:tools 0 :function :parameters :required])))

(deftest an-optional-top-level-output-is-offered-to-the-provider-as-nullable
  (let [captured (atom nil)]
    (with-redefs [router/supports-function-calling? (constantly true)
                  router/completion (fn [_provider request]
                                      (reset! captured request)
                                      {:choices [{:message {:tool-calls
                                                            [{:function {:name "submit_response"
                                                                         :arguments "{\"answer\":\"Paris\"}"}}]}}]})]
      (llm/predict :test answer+optional-aside {} {:validate? false})
      (let [props (tool-properties @captured)]
        (is (= {:type "string"} (get props "answer"))
            "a required field's wire type is unchanged")
        (is (= {:oneOf [{:type "string"} {:type "null"}]} (get props "aside"))
            "an optional field's wire type admits null")
        (is (= #{"answer"} (tool-required @captured))
            "the required list itself is unchanged")))))

(def ^:private items-with-optional-nested-note
  {:inputs []
   :outputs [{:name :items
              :spec [:vector [:map [:id :string] [:note {:optional true} :string]]]}]})

(deftest an-optional-entry-nested-in-a-vector-item-is-offered-as-nullable
  (let [captured (atom nil)]
    (with-redefs [router/supports-function-calling? (constantly true)
                  router/completion (fn [_provider request]
                                      (reset! captured request)
                                      {:choices [{:message {:tool-calls
                                                            [{:function {:name "submit_response"
                                                                         :arguments "{\"items\":[]}"}}]}}]})]
      (llm/predict :test items-with-optional-nested-note {} {:validate? false})
      (let [item-props (get-in (tool-properties @captured) ["items" :items :properties])]
        (is (= {:type "string"} (get item-props "id"))
            "a required nested entry's wire type is unchanged")
        (is (= {:oneOf [{:type "string"} {:type "null"}]} (get item-props "note"))
            "an optional nested vector-item entry admits null")))))

(def ^:private decision-with-optional-multi-branch-entry
  {:inputs []
   :outputs [{:name :decision
              :spec [:multi {:dispatch :type}
                     [:a [:map [:type [:= :a]] [:value {:optional true} :string]]]
                     [:b [:map [:type [:= :b]] [:count :int]]]]}]})

(deftest an-optional-entry-nested-in-a-multi-branch-is-offered-as-nullable
  (let [captured (atom nil)]
    (with-redefs [router/supports-function-calling? (constantly true)
                  router/completion (fn [_provider request]
                                      (reset! captured request)
                                      {:choices [{:message {:tool-calls
                                                            [{:function {:name "submit_response"
                                                                         :arguments "{\"decision\":{\"type\":\"a\"}}"}}]}}]})]
      (llm/predict :test decision-with-optional-multi-branch-entry {} {:validate? false})
      (let [branches (get-in (tool-properties @captured) ["decision" :oneOf])
            branch-a (first (filter #(= "a" (get-in % [:properties "type" :const])) branches))
            branch-b (first (filter #(= "b" (get-in % [:properties "type" :const])) branches))]
        (is (= {:oneOf [{:type "string"} {:type "null"}]} (get-in branch-a [:properties "value"]))
            "an optional entry in one :multi branch admits null")
        (is (= {:type "integer"} (get-in branch-b [:properties "count"]))
            "a required entry in another :multi branch is unchanged")))))

(deftest the-marker-prompt-also-advertises-nullable-optionals-at-any-depth
  (let [captured (atom nil)]
    (with-redefs [router/supports-function-calling? (constantly false)
                  router/completion (fn [_provider request]
                                      (reset! captured request)
                                      {:choices [{:message {:content "[[ ## items ## ]]\n[]"}}]})]
      (llm/predict :test items-with-optional-nested-note {} {:validate? false})
      (let [content (get-in @captured [:messages 0 :content])]
        (is (str/includes? content "note?: str or null")
            "the rendered marker prompt tells the model the nested optional entry admits null")
        (is (str/includes? content "id: str")
            "a required nested entry's rendered type is unchanged")
        (is (not (str/includes? content "id: str or null")))))))

(deftest a-null-top-level-optional-output-becomes-absent
  (with-redefs [router/supports-function-calling? (constantly true)
                router/completion (fn [& _]
                                    {:choices [{:message {:tool-calls
                                                          [{:function {:name "submit_response"
                                                                       :arguments "{\"answer\":\"Paris\",\"aside\":null}"}}]}}]})]
    (is (= {:answer "Paris"}
           (llm/predict :test answer+optional-aside {} {:validate? true}))
        "a null optional field is dropped, not returned as a present null")))

(deftest a-null-top-level-required-output-still-fails-validation
  (with-redefs [router/supports-function-calling? (constantly true)
                router/completion (fn [& _]
                                    {:choices [{:message {:tool-calls
                                                          [{:function {:name "submit_response"
                                                                       :arguments "{\"answer\":null}"}}]}}]})]
    (let [failure (try
                    (llm/predict :test answer+optional-aside {} {:validate? true})
                    (catch clojure.lang.ExceptionInfo e e))]
      (is (instance? clojure.lang.ExceptionInfo failure))
      (is (= :schema-validation-failed (:failure-kind (ex-data failure)))))))

(deftest a-null-nested-map-entry-becomes-absent
  (with-redefs [router/supports-function-calling? (constantly true)
                router/completion
                (fn [& _]
                  {:choices
                   [{:message
                     {:tool-calls
                      [{:function
                        {:name "submit_response"
                         :arguments
                         "{\"decision\":{\"action\":\"invoke\",\"request\":{\"action\":\"beliefs\",\"note\":null}}}"}}]}}]})]
    (is (= {:decision {:action :invoke :request {:action :beliefs}}}
           (llm/predict :test
                        {:inputs []
                         :outputs [{:name :decision
                                    :spec [:map
                                           [:action [:enum :invoke]]
                                           [:request [:map
                                                      [:action [:= :beliefs]]
                                                      [:note {:optional true} :string]]]]}]}
                        {}
                        {:validate? true})))))

(deftest a-null-required-nested-map-entry-still-fails-validation
  (with-redefs [router/supports-function-calling? (constantly true)
                router/completion
                (fn [& _]
                  {:choices
                   [{:message
                     {:tool-calls
                      [{:function
                        {:name "submit_response"
                         :arguments
                         "{\"decision\":{\"action\":\"invoke\",\"request\":{\"action\":null,\"note\":\"ok\"}}}"}}]}}]})]
    (let [failure (try
                    (llm/predict :test
                                 {:inputs []
                                  :outputs [{:name :decision
                                             :spec [:map
                                                    [:action [:enum :invoke]]
                                                    [:request [:map
                                                               [:action [:= :beliefs]]
                                                               [:note {:optional true} :string]]]]}]}
                                 {}
                                 {:validate? true})
                    (catch clojure.lang.ExceptionInfo e e))]
      (is (instance? clojure.lang.ExceptionInfo failure))
      (is (= :schema-validation-failed (:failure-kind (ex-data failure)))))))

(deftest a-null-vector-item-entry-becomes-absent
  (with-redefs [router/supports-function-calling? (constantly true)
                router/completion
                (fn [& _]
                  {:choices
                   [{:message
                     {:tool-calls
                      [{:function
                        {:name "submit_response"
                         :arguments
                         "{\"items\":[{\"id\":\"a\",\"note\":null},{\"id\":\"b\",\"note\":\"ok\"}]}"}}]}}]})]
    (is (= {:items [{:id "a"} {:id "b" :note "ok"}]}
           (llm/predict :test items-with-optional-nested-note {} {:validate? true})))))

(deftest a-null-multi-branch-entry-becomes-absent
  (with-redefs [router/supports-function-calling? (constantly true)
                router/completion
                (fn [& _]
                  {:choices
                   [{:message
                     {:tool-calls
                      [{:function
                        {:name "submit_response"
                         :arguments "{\"decision\":{\"type\":\"a\",\"value\":null}}"}}]}}]})]
    (is (= {:decision {:type :a}}
           (llm/predict :test decision-with-optional-multi-branch-entry {} {:validate? true})))))

(def ^:private answer+maybe-optional-aside
  {:inputs []
   :outputs [{:name :answer :spec :string}
             {:name :aside :spec [:maybe :string] :optional true}]})

(deftest an-optional-field-declared-maybe-keeps-a-returned-null
  (with-redefs [router/supports-function-calling? (constantly true)
                router/completion (fn [& _]
                                    {:choices [{:message {:tool-calls
                                                          [{:function {:name "submit_response"
                                                                       :arguments "{\"answer\":\"Paris\",\"aside\":null}"}}]}}]})]
    (is (= {:answer "Paris" :aside nil}
           (llm/predict :test answer+maybe-optional-aside {} {:validate? true}))
        "a field already declared [:maybe ...] keeps its null — it is the author's meaning")))

(deftest a-null-optional-nested-vector-entry-in-marker-mode-becomes-absent
  (with-redefs [router/supports-function-calling? (constantly false)
                router/completion (fn [& _]
                                    {:choices [{:message
                                                {:content
                                                 "[[ ## items ## ]]\n[{\"id\":\"a\",\"note\":null}]"}}]})]
    (is (= {:items [{:id "a"}]}
           (llm/predict :test items-with-optional-nested-note {} {:validate? true})))))

(deftest streaming-final-result-drops-a-null-optional-nested-entry-at-any-depth
  (with-redefs [router/completion
                (fn [& _]
                  (fake-stream [{:choices [{:delta {:content "[[ ## items ## ]]\n[{\"id\":\"a\",\"note\":null},{\"id\":\"b\",\"note\":\"ok\"}]"}}]}]))]
    (let [events (drain (llm/predict-stream-v2 :test items-with-optional-nested-note {}
                                               {:validate? true :debounce-ms 0}))
          final (last events)]
      (is (= :final (:orc/event final)))
      (is (= {:items [{:id "a"} {:id "b" :note "ok"}]} (:outputs final))))))

(deftest streaming-final-result-still-fails-a-null-required-nested-entry
  (with-redefs [router/completion
                (fn [& _]
                  (fake-stream [{:choices [{:delta {:content "[[ ## items ## ]]\n[{\"id\":null}]"}}]}]))]
    (let [events (drain (llm/predict-stream-v2 :test items-with-optional-nested-note {}
                                               {:validate? true :debounce-ms 0}))
          final (last events)]
      (is (= :error (:orc/event final))
          "a null in a REQUIRED nested entry still fails validation, even streamed"))))

;; ---------------------------------------------------------------------------
;; Dropping a null optional is normalization, not validation — weed found
;; that `drop-null-optional-outputs` ran only inside the `validate? true`
;; branch of both `predict` and `predict-stream-v2`. Real callers run with
;; :validate? false (evaluation/core/judges.clj, gepa/core/todo_processors.clj,
;; mcp-sheet-builder's patterns.clj/intent_analyzer.clj): the wire now offers
;; every optional field as nullable regardless of :validate?, so those
;; callers would have started receiving PRESENT nulls they never saw before,
;; violating "a null for an optional field is absence... never a present
;; null" for the one flag real callers actually use. :validate? must only
;; decide whether `validate-outputs` runs — normalization runs either way.
;; ---------------------------------------------------------------------------

(deftest a-null-top-level-optional-output-becomes-absent-with-validation-off
  (with-redefs [router/supports-function-calling? (constantly true)
                router/completion (fn [& _]
                                    {:choices [{:message {:tool-calls
                                                          [{:function {:name "submit_response"
                                                                       :arguments "{\"answer\":\"Paris\",\"aside\":null}"}}]}}]})]
    (is (= {:answer "Paris"}
           (llm/predict :test answer+optional-aside {} {:validate? false}))
        "the null optional is dropped even though :validate? is false")))

(deftest a-null-optional-nested-vector-entry-becomes-absent-with-validation-off
  (with-redefs [router/supports-function-calling? (constantly true)
                router/completion
                (fn [& _]
                  {:choices
                   [{:message
                     {:tool-calls
                      [{:function
                        {:name "submit_response"
                         :arguments
                         "{\"items\":[{\"id\":\"a\",\"note\":null},{\"id\":\"b\",\"note\":\"ok\"}]}"}}]}}]})]
    (is (= {:items [{:id "a"} {:id "b" :note "ok"}]}
           (llm/predict :test items-with-optional-nested-note {} {:validate? false})))))

(deftest a-null-required-output-is-returned-as-is-with-validation-off
  (with-redefs [router/supports-function-calling? (constantly true)
                router/completion (fn [& _]
                                    {:choices [{:message {:tool-calls
                                                          [{:function {:name "submit_response"
                                                                       :arguments "{\"answer\":null,\"aside\":null}"}}]}}]})]
    (is (= {:answer nil}
           (llm/predict :test answer+optional-aside {} {:validate? false}))
        "with validation off, a null REQUIRED field is returned exactly as
         the provider sent it — normalization never invents or removes a
         required value, it only drops a null OPTIONAL one")))

(deftest a-null-required-nested-entry-is-returned-as-is-with-validation-off
  (with-redefs [router/supports-function-calling? (constantly true)
                router/completion
                (fn [& _]
                  {:choices
                   [{:message
                     {:tool-calls
                      [{:function
                        {:name "submit_response"
                         :arguments "{\"items\":[{\"id\":null,\"note\":null}]}"}}]}}]})]
    (is (= {:items [{:id nil}]}
           (llm/predict :test items-with-optional-nested-note {} {:validate? false}))
        "the required :id stays present with its null; the optional :note is dropped")))

(deftest streaming-final-result-drops-a-null-optional-nested-entry-with-validation-off
  (with-redefs [router/completion
                (fn [& _]
                  (fake-stream [{:choices [{:delta {:content "[[ ## items ## ]]\n[{\"id\":\"a\",\"note\":null},{\"id\":\"b\",\"note\":\"ok\"}]"}}]}]))]
    (let [events (drain (llm/predict-stream-v2 :test items-with-optional-nested-note {}
                                               {:validate? false :debounce-ms 0}))
          final (last events)]
      (is (= :final (:orc/event final)))
      (is (= {:items [{:id "a"} {:id "b" :note "ok"}]} (:outputs final))
          "streamed and unvalidated, the optional null is still absent"))))

(deftest streaming-final-result-returns-a-null-required-entry-as-is-with-validation-off
  (with-redefs [router/completion
                (fn [& _]
                  (fake-stream [{:choices [{:delta {:content "[[ ## items ## ]]\n[{\"id\":null,\"note\":null}]"}}]}]))]
    (let [events (drain (llm/predict-stream-v2 :test items-with-optional-nested-note {}
                                               {:validate? false :debounce-ms 0}))
          final (last events)]
      (is (= :final (:orc/event final))
          "nothing is invented or removed for the required field, and there
           is no validation to fail")
      (is (= {:items [{:id nil}]} (:outputs final))))))

;; ---------------------------------------------------------------------------
;; Wire/decode symmetry — `nullable-optionals` (wire) and `drop-null-optionals`
;; (decode) must walk the SAME schema shapes, or a shape can be told "null is
;; fine" on the wire while decode has no way to turn that null back into
;; absence — a real defect found by inspection: `nullable-optionals` used to
;; fall through to a generic catch-all that recursed into every vector-shaped
;; form (including :or, :and, :sequential, :set, :tuple, :map-of), while
;; `drop-null-optionals` only handled :map/:vector/:maybe/:multi explicitly.
;; That is WORSE than not rewriting the wire at all: the provider is told a
;; null is acceptable and then validation rejects it anyway.
;;
;; This reaches into `ai.obney.orc.llm.core` directly (both functions are
;; `defn-`) because the property under test is an internal structural
;; invariant between two private helpers — the shared `descend` table both
;; now read from — not a behavior naturally expressed once through the
;; public boundary for every shape without per-shape provider-response
;; plumbing duplicating what this table already proves once, generically.
;; ---------------------------------------------------------------------------

(def ^:private nullable-optionals* @#'core/nullable-optionals)
(def ^:private drop-null-optionals* @#'core/drop-null-optionals)

(def ^:private wire-decode-inner
  "The exact probe from the reported defect: a required discriminator plus
   one optional, non-nullable entry."
  [:map [:kind [:enum :a]] [:note {:optional true} [:string {:min 1}]]])

(def ^:private wire-decode-inner-null-value
  {:kind :a :note nil})

(def ^:private wire-decode-shapes
  "One row per Malli composite shape `wire-decode-inner` can sit inside.
   `:rewritten?` states whether `descend` is expected to walk into that
   shape (and so make `inner`'s optional :note entry nullable on the wire);
   `:schema`/`:value` embed `inner` and its null-note value at that position."
  [{:name "vector item"     :rewritten? true
    :schema [:vector wire-decode-inner]                  :value [wire-decode-inner-null-value]}
   {:name "sequential item" :rewritten? true
    :schema [:sequential wire-decode-inner]               :value [wire-decode-inner-null-value]}
   {:name "set item"        :rewritten? true
    :schema [:set wire-decode-inner]                      :value #{wire-decode-inner-null-value}}
   {:name "tuple position"  :rewritten? true
    :schema [:tuple wire-decode-inner]                    :value [wire-decode-inner-null-value]}
   {:name "map-of value"    :rewritten? true
    :schema [:map-of :keyword wire-decode-inner]          :value {:x wire-decode-inner-null-value}}
   {:name "or branch"       :rewritten? false
    :schema [:or wire-decode-inner]                       :value wire-decode-inner-null-value}
   {:name "and branch"      :rewritten? false
    :schema [:and wire-decode-inner]                      :value wire-decode-inner-null-value}])

(deftest optional-nullable-wire-and-decode-cannot-diverge-across-schema-shapes
  (doseq [{:keys [name rewritten? schema value]} wire-decode-shapes]
    (testing name
      (let [wire (nullable-optionals* schema)]
        (if rewritten?
          (do
            (is (not= wire schema)
                "a shape descend walks into must actually be rewritten for an optional entry")
            (is (m/validate wire value)
                "a rewritten shape's wire form must admit the probe's null note")
            (is (m/validate schema (drop-null-optionals* schema value))
                "wire admitting null obligates decode-then-validate to succeed"))
          (do
            (is (= wire schema)
                "a shape descend does not walk into must reach the provider exactly as declared")
            (is (not (m/validate schema value))
                "sanity: the probe's null genuinely violates the ORIGINAL, un-rewritten schema")
            (is (= value (drop-null-optionals* schema value))
                "decode must not touch a value under a shape it never walks into")
            (is (not (m/validate schema (drop-null-optionals* schema value)))
                "an un-rewritten shape's null must still fail validation, exactly as before this fix")))))))

(deftest a-marker-repeated-after-prose-wins-and-takes-the-answer-with-it
  ;; sio #11 relaxed the marker to be recognised after prose, and the parser
  ;; takes the LAST match. Together they mean a model that refers back to its
  ;; own marker overwrites its real answer with the words that follow the
  ;; reference. At 91e7d100 this field was "true\n\nsee [[ ## verdict ## ]]
  ;; above"; it is now "above", and the "true" is gone.
  ;;
  ;; This is the CC-4b grounded-verdict family. The direction is SAFE, and it is
  ;; safe by construction rather than by luck: the strict extractor pinned in
  ;; `cc4c_strict_verdict_extraction_test` establishes a verdict only from an
  ;; exact one-word "true"/"false", so "above" reads as fail-CLOSED — as did the
  ;; longer prose before it. Pinned here so a later parser change cannot quietly
  ;; turn this field into something that reads OPEN.
  (with-redefs [router/supports-function-calling? (constantly false)
                router/completion
                (fn [& _]
                  {:choices [{:message {:content "[[ ## verdict ## ]]\ntrue\n\nsee [[ ## verdict ## ]] above"}}]})]
    (is (= {:verdict "above"}
           (llm/predict :test verdict-spec {:question "Grounded?"} {:validate? false})))))

;; --------------------------------------------------------------------------- ;;
;; DeclaredMeaningReachesTheModel (llm.allium): input descriptions reach the
;; model whether the request is marker-form or function-calling.
;; --------------------------------------------------------------------------- ;;

(def ^:private described-inputs-spec
  {:inputs [{:name :claim :spec :string :description "The claim under review"}
            {:name :evidence :spec :string :description "Evidence offered for the claim"}
            {:name :note :spec :string}]
   :outputs [{:name :answer :spec :string :description "The verdict"}]
   :instructions "Judge the claim."})

(def ^:private described-inputs-values
  {:claim "Water boils at 100C" :evidence "Textbook chapter 3" :note "n/a"})

(defn- captured-user-text
  [function-calling?]
  (let [captured (atom nil)]
    (with-redefs [router/supports-function-calling? (constantly function-calling?)
                  router/completion
                  (fn [_provider request]
                    (reset! captured request)
                    (if function-calling?
                      {:choices [{:message {:tool-calls
                                            [{:function {:name "submit_response"
                                                         :arguments "{\"answer\":\"ok\"}"}}]}}]}
                      {:choices [{:message {:content "[[ ## answer ## ]]\nok"}}]}))]
      (llm/predict :test described-inputs-spec described-inputs-values {:validate? false})
      (let [content (get-in @captured [:messages 0 :content])]
        (if (string? content) content (pr-str content))))))

(deftest function-calling-request-carries-each-input-description-beside-its-value
  (let [text (captured-user-text true)]
    (is (str/includes? text "The claim under review"))
    (is (str/includes? text "Evidence offered for the claim"))
    (is (str/includes? text "Water boils at 100C"))
    (is (str/includes? text "Textbook chapter 3"))
    (testing "description sits next to its own value"
      (is (re-find #"(?s)claim[^\n]*The claim under review[^\n]*Water boils at 100C|claim[^\n]*Water boils at 100C" text))
      (is (< (str/index-of text "The claim under review")
             (str/index-of text "Water boils at 100C")
             (str/index-of text "Evidence offered for the claim")
             (str/index-of text "Textbook chapter 3"))))
    (testing "an input without a description renders as before"
      (is (str/includes? text "note: n/a")))))

(deftest marker-request-body-is-unchanged-by-input-description-rendering
  ;; Baseline captured from the marker path before the function-calling fix.
  (is (= (str "Your input fields are:\n1. `claim` (str): The claim under review\n"
              "2. `evidence` (str): Evidence offered for the claim\n3. `note` (str): \n"
              "Your output fields are:\n1. `answer` (str): The verdict\n"
              "All interactions will be structured in the following way, with the appropriate values filled in.\n\n"
              "[[ ## claim ## ]]\n{claim}\n\n[[ ## evidence ## ]]\n{evidence}\n\n[[ ## note ## ]]\n{note}\n\n"
              "[[ ## answer ## ]]\n{answer}\n[[ ## completed ## ]]\n"
              "In adhering to this structure, your instructions are: Judge the claim.\n\n"
              "[[ ## claim ## ]]\nWater boils at 100C\n\n[[ ## evidence ## ]]\nTextbook chapter 3\n\n"
              "[[ ## note ## ]]\nn/a")
         (captured-user-text false))))
