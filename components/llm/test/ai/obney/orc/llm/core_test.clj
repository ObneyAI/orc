(ns ai.obney.orc.llm.core-test
  (:require [clojure.core.async :as async]
            [clojure.test :refer [deftest is testing]]
            [litellm.router :as router]
            [litellm.providers.openrouter :as openrouter]
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
                           :max-tokens 512})))
      (is (= :none (:reasoning-effort @captured-request)))
      (is (= 512 (:max-tokens @captured-request))))))

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
