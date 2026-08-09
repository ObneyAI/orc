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
  (is (= '#{predict predict-stream-v2 register-provider! quick-setup! list-providers}
         (set (keys (ns-publics 'ai.obney.orc.llm.interface))))))

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
                    ;; :tool-choice, not :tool_choice — this assertion previously
                    ;; pinned the underscore key, which is precisely why the dropped
                    ;; tool_choice went unnoticed: the request map we build was
                    ;; internally consistent with the test, and the mismatch only
                    ;; bit one layer further in, at litellm's provider transform.
                    (is (= "submit_response" (get-in request [:tool-choice :function :name])))
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

(deftest forced-tool-choice-survives-the-openrouter-transform
  (let [captured (atom nil)]
    (with-redefs [router/supports-function-calling? (constantly true)
                  router/completion (fn [_provider request]
                                      (reset! captured request)
                                      {:choices [{:message {:tool_calls [{:function {:name "submit_response"
                                                                                     :arguments "{\"answer\":\"Paris\"}"}}]}}]})]
      (llm/predict :openrouter qa {:question "Capital?"} {:validate? false}))

    (testing "we ask litellm to force the tool call using the key it actually reads"
      (is (some? (:tool-choice @captured))
          "litellm's OpenRouter provider reads (:tool-choice request); an underscore
           :tool_choice is a different keyword and is silently dropped"))

    (testing "and it survives into the transformed provider request"
      (let [wire (openrouter/transform-request-impl :openrouter @captured {})]
        (is (= {:type "function" :function {:name "submit_response"}}
               (:tool_choice wire))
            "tool_choice must be present in the request litellm actually sends, or the
             submit_response call is advisory rather than forced")
        (is (seq (:tools wire))
            "the tool definition itself must still be there")))))
