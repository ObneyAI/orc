(ns ai.obney.orc.llm.paired-function-output-test
  "PairedFunctionOutputPreservesTheDeclaredContract: blocking HTTP boundary."
  (:require [clojure.test :refer [deftest is]]
            [clojure.core.async :as async]
            [clojure.string :as str]
            [cheshire.core :as json]
            [hato.client :as http]
            [ai.obney.orc.llm.interface :as llm]))

(deftest paired-function-preserves-described-typed-keys-with-validation-disabled
  (let [spec {:instructions "Return both independent identities."
              :inputs [{:name :source :spec :string :description "Source identity evidence."}]
              :outputs [{:name :payload :description "Identity result."
                         :spec [:map {:closed true}
                                [:identity [:map {:closed true :description "Two independent typed identities."}
                                            [:same [:string {:description "Keyword slot text."}]]
                                            ["same" [:int {:description "String slot integer; zero is valid."}]]]]]}]}
        captured (atom nil)]
    (doseq [inherited? [true false]]
      (llm/register-provider! :paired-function
        (cond-> {:provider :openrouter :model "controlled/model"
                 :config {:api-key "synthetic-not-sent"}}
          inherited? (assoc :output-mode :function-calling)))
      (let [result
            (with-redefs [http/post
                          (fn [_ options]
                            (reset! captured (json/parse-string (:body options) true))
                            (future
                              {:status 200
                               :body {:id "paired-response" :model "controlled/resolved"
                                      :usage {:prompt_tokens 12 :completion_tokens 8 :total_tokens 20}
                                      :choices [{:finish_reason "tool_calls"
                                                 :message {:role "assistant" :content nil
                                                           :tool_calls [{:id "paired-call" :type "function"
                                                                         :function {:name "submit_response"
                                                                                    :arguments "{\"payload\":{\"identity\":{\"__sio_key_0\":\"keyword-value\",\"__sio_key_1\":0}}}"}}]}}]}}))]
              (llm/predict :paired-function spec {:source "original"}
                           (cond-> {:validate? false :use-function-calling? false :with-metadata? true :with-provider-evidence? true
                                    :extra-body {:provider {:data_collection "deny" :allow_fallbacks false}}}
                             (not inherited?) (assoc :output-mode :function-calling))))
            request @captured
            function (get-in request [:tools 0 :function])
            properties (get-in function [:parameters :properties :payload :properties :identity :properties])
            prompt (get-in request [:messages 0 :content])]
        (is (= {:payload {:identity {:same "keyword-value" "same" 0}}} (:outputs result)))
        (is (= #{:__sio_key_0 :__sio_key_1} (set (keys properties))))
        (is (= "Original map key (keyword): :same.\nKeyword slot text." (:description (:__sio_key_0 properties))))
        (is (= "Original map key (string): \"same\".\nString slot integer; zero is valid." (:description (:__sio_key_1 properties))))
        (is (str/includes? prompt "Source identity evidence."))
        (is (str/includes? prompt "Identity result."))
        (is (= "auto" (:tool_choice request)))
        (is (str/includes? prompt "Response function argument contract (JSON Schema):"))
        (is (str/includes? prompt "Call submit_response with one argument object conforming to this contract."))
        (is (not (str/includes? prompt "Return one JSON object conforming to this contract.")))
        (is (nil? (:response_format request)))
        (is (true? (:strict function)))
        (is (= "deny" (get-in request [:provider :data_collection])))
        (is (true? (get-in request [:provider :require_parameters])))
        (is (false? (get-in request [:provider :allow_fallbacks])))
        (is (= "controlled/resolved" (:model result)))
        (is (= "paired-response" (get-in result [:provider-evidence :response-id])))
        (is (map? (:provider-observations result)))))))

(deftest paired-function-distinguishes-empty-response-from-missing-forced-call
  ;; StructuredFailureClassification through the real OpenRouter adapter. A
  ;; protocol failure must retain safe returned evidence without another call.
  (let [spec {:instructions "Return the supplied identity."
              :inputs [{:name :source :spec :string}]
              :outputs [{:name :identity :spec :string}]}
        usage {:prompt-tokens 12 :completion-tokens 8 :total-tokens 20}]
    (llm/register-provider! :paired-envelope
      {:provider :openrouter :model "controlled/model"
       :output-mode :function-calling
       :config {:api-key "synthetic-not-sent"}})
    (doseq [[choices failure-kind observed-choices]
            [[[] :empty-provider-response []]
             [[{:index 0 :finish_reason "stop"
                :message {:role "assistant" :content "private-response-content"
                          :reasoning "Returned explanation."
                          :private-message "private-response-metadata"}}]
              :missing-forced-tool-call
              [{:index 0 :finish-reason :stop
                :reasoning-content "Returned explanation."}]]]]
      (let [requests (atom [])
            error (with-redefs [http/post
                               (fn [_ options]
                                 (swap! requests conj (json/parse-string (:body options) true))
                                 (future
                                   {:status 200
                                    :body {:id "envelope-response" :model "controlled/resolved"
                                           :usage {:prompt_tokens 12 :completion_tokens 8 :total_tokens 20}
                                           :private-response "private-response-metadata"
                                           :choices choices}}))]
                    (try (llm/predict :paired-envelope spec {:source "original"}
                                     {:validate? false :with-metadata? true
                                      :with-provider-evidence? true :max-retries 0 :force-tool-choice? true})
                         nil
                         (catch clojure.lang.ExceptionInfo error error)))
            data (ex-data error)]
        (is (= failure-kind (:failure-kind data)))
        (is (= {:provider "paired-envelope" :model "controlled/resolved"
                :response-id "envelope-response"
                :finish-reason (when (seq choices) "stop")
                :tool-call-present? false :tool-call-name nil
                :usage usage :output-truncated? false}
               (:provider-evidence data)))
        (is (= {:provider "paired-envelope" :model "controlled/resolved"
                :response-id "envelope-response" :usage usage
                :choices observed-choices}
               (:provider-observations data)))
        (is (not (str/includes? (pr-str data) "private-response")))
        (is (= 1 (count @requests)))
        (is (= {:type "function" :function {:name "submit_response"}}
               (:tool_choice (first @requests))))
        (is (seq (:tools (first @requests))))
        (is (nil? (:response_format (first @requests))))))))

(deftest paired-function-distinguishes-malformed-arguments-from-invalid-values
  (llm/register-provider! :paired-argument-classification
    {:provider :openrouter :model "controlled/model" :output-mode :function-calling
     :config {:api-key "synthetic-not-sent"}})
  (doseq [[arguments expected-kind]
          [["{" :tool-call-parsing-failed]
           ["{} {}" :tool-call-parsing-failed]
           ["{\"identity\":7}" :schema-validation-failed]
           ["{\"identity\":\"a\",\"identity\":\"b\"}" :schema-validation-failed]]]
    (let [calls (atom 0)
          failure
          (with-redefs [http/post
                        (fn [& _]
                          (swap! calls inc)
                          (future {:status 200
                                   :body {:id "argument-classification" :model "controlled/resolved"
                                          :choices [{:finish_reason "tool_calls"
                                                     :message {:role "assistant" :content nil
                                                               :tool_calls [{:type "function"
                                                                             :function {:name "submit_response"
                                                                                        :arguments arguments}}]}}]}}))]
            (try (llm/predict :paired-argument-classification
                             {:inputs [] :outputs [{:name :identity :spec :string}]} {}
                             {:validate? false :with-provider-evidence? true})
                 nil
                 (catch clojure.lang.ExceptionInfo e e)))]
      (is (= expected-kind (:failure-kind (ex-data failure))) arguments)
      (is (= "argument-classification" (get-in (ex-data failure) [:provider-evidence :response-id])))
      (is (= 1 @calls) "a bad argument string is not a protocol retry"))))


(deftest paired-function-rejects-unusable-function-envelopes
  (llm/register-provider! :paired-envelope-rejection
    {:provider :openrouter :model "controlled/model" :output-mode :function-calling
     :config {:api-key "synthetic-not-sent"}})
  (let [call {:type "function" :function {:name "submit_response" :arguments "{\"identity\":\"original\"}"}}
        message {:role "assistant" :content "This content must not replace function arguments."
                 :tool_calls [call]}
        choice {:finish_reason "tool_calls" :message message}]
    (doseq [[label choices expected-kind]
            [[:wrong-name [(assoc-in choice [:message :tool_calls 0 :function :name] "another_response")] :tool-call-parsing-failed]
             [:multiple-calls [(assoc-in choice [:message :tool_calls] [call call])] :tool-call-parsing-failed]
             [:wrong-type [(assoc-in choice [:message :tool_calls 0 :type] "another_type")] :tool-call-parsing-failed]
             [:non-string-arguments [(assoc-in choice [:message :tool_calls 0 :function :arguments] {:identity "original"})] :tool-call-parsing-failed]
             [:multiple-choices [choice choice] :tool-call-parsing-failed]
             [:truncated [(assoc choice :finish_reason "length")] :schema-validation-failed]
             [:refused [(assoc-in choice [:message :refusal] "Controlled refusal")] :schema-validation-failed]
             [:filtered [(assoc choice :finish_reason "content_filter")] :schema-validation-failed]
             [:provider-error [(assoc choice :finish_reason "error")] :provider-finish-error]]]
      (let [calls (atom 0)
            error (with-redefs [http/post
                               (fn [& _]
                                 (swap! calls inc)
                                 (future {:status 200
                                          :body {:id "rejected-envelope" :model "controlled/resolved"
                                                 :choices choices}}))]
                    (try (llm/predict :paired-envelope-rejection
                                     {:inputs [] :outputs [{:name :identity :spec :string}]} {}
                                     {:validate? false :with-provider-evidence? true})
                         nil
                         (catch clojure.lang.ExceptionInfo e e)))]
        (is (= expected-kind (:failure-kind (ex-data error))) (name label))
        (is (= "rejected-envelope" (get-in (ex-data error) [:provider-evidence :response-id])))
        (is (= 1 @calls) "No content, marker or protocol retry can repair a rejected envelope")))))

(deftest paired-function-preflight-and-streaming-never-dispatch-markers
  (let [spec {:inputs [] :outputs [{:name :identity :spec :string}]}
        calls (atom 0)]
    (llm/register-provider! :paired-preflight-rejection
      {:provider :openrouter :model "controlled/model" :output-mode :function-calling
       :config {:api-key "synthetic-not-sent"}})
    (with-redefs [http/post (fn [& _] (swap! calls inc)
                             (throw (java.io.IOException. "No preflight HTTP allowed")))]
      (doseq [options [{:tools []}
                       {:tool-choice "auto"}
                       {:response-format {:type "json_object"}}
                       {:stream true}
                       {:extra-body {:tools []}}
                       {:extra-body {:provider {:require_parameters false}}}
                       {:extra-body {"provider" {"require_parameters" false}}}]]
        (let [failure (try (llm/predict :paired-preflight-rejection spec {} options)
                           nil (catch clojure.lang.ExceptionInfo e e))]
          (is (= :schema-validation-failed (:failure-kind (ex-data failure))) (pr-str options))
          (is (zero? @calls))))
      (let [channel (llm/predict-stream-v2 :paired-preflight-rejection spec {})
            [event selected] (async/alts!! [channel (async/timeout 3000)])]
        (is (= channel selected))
        (is (= :error (:orc/event event)))
        (is (zero? @calls)))
      (llm/register-provider! :paired-unsupported-adapter
        {:provider :openai :model "controlled/model" :output-mode :function-calling
         :config {:api-key "synthetic-not-sent"}})
      (let [failure (try (llm/predict :paired-unsupported-adapter spec {})
                         nil (catch clojure.lang.ExceptionInfo e e))]
        (is (= :schema-validation-failed (:failure-kind (ex-data failure))))
        (is (zero? @calls))))))

(deftest paired-function-preserves-nested-presence-dictionaries-and-literal-values
  (let [dictionary-schema
        [:map-of {:min 2 :max 2 :description "Two independent typed source decisions."}
         [:enum {:description "Exact decision key identities."
                 :descriptions {:same "Keyword decision identity." "same" "String decision identity."}}
          :same "same"] :boolean]
        record-schema
        [:map {:closed true :description "Original source record; preserve presence independently."}
         [:id :string]
         [:annotation {:optional true} [:maybe {:description "Absent, explicit null, or supplied annotation."} :string]]
         [:active :boolean] [:quantity :int]
         [:literal [:string {:description "Literal text, even when it looks like JSON."}]]
         [:identity [:map {:closed true}
                     [:same [:string {:description "Keyword identity text."}]]
                     ["same" [:int {:description "String identity number."}]]]]]
        packet-schema [:map {:closed true :description "Full source packet without repair."}
                       [:records [:vector record-schema]]
                       [:decisions dictionary-schema]]
        records [{:id "absent" :active false :quantity 0 :literal "{\"x\":null}" :identity {:same "K" "same" 0}}
                 {:id "null" :annotation nil :active false :quantity 0 :literal "null" :identity {:same "K" "same" 0}}
                 {:id "text" :annotation "null" :active true :quantity 1 :literal "[false,0]" :identity {:same "K" "same" 0}}]
        expected {:packet {:records records :decisions {:same false "same" true}}}
        wire {:packet {:records
                       [{:id "absent" :annotation {:presence "absent"} :active false :quantity 0
                         :literal "{\"x\":null}" :identity {:__sio_key_0 "K" :__sio_key_1 0}}
                        {:id "null" :annotation {:presence "present" :value nil} :active false :quantity 0
                         :literal "null" :identity {:__sio_key_0 "K" :__sio_key_1 0}}
                        {:id "text" :annotation {:presence "present" :value "null"} :active true :quantity 1
                         :literal "[false,0]" :identity {:__sio_key_0 "K" :__sio_key_1 0}}]
                       :decisions [{:key {:type "keyword" :value "same"} :value false}
                                   {:key {:type "string" :value "same"} :value true}]}}
        requests (atom [])]
    (llm/register-provider! :paired-rich-packet
      {:provider :openrouter :model "controlled/model" :output-mode :function-calling
       :config {:api-key "synthetic-not-sent"}})
    (let [outputs (with-redefs [http/post
                               (fn [_ options]
                                 (swap! requests conj (json/parse-string (:body options) true))
                                 (future {:status 200
                                          :body {:id "rich-packet" :model "controlled/resolved"
                                                 :choices [{:finish_reason "tool_calls"
                                                            :message {:role "assistant" :content nil
                                                                      :tool_calls [{:type "function"
                                                                                    :function {:name "submit_response"
                                                                                               :arguments (json/generate-string wire)}}]}}]}}))]
                    (llm/predict :paired-rich-packet
                                 {:inputs [] :outputs [{:name :packet :spec packet-schema}]} {}
                                 {:validate? false :use-function-calling? false}))
          [absent null text] (get-in outputs [:packet :records])
          request (first @requests)]
      (is (= expected outputs))
      (is (not (contains? absent :annotation)))
      (is (and (contains? null :annotation) (nil? (:annotation null))))
      (is (= "null" (:annotation text)))
      (is (false? (:active absent)))
      (is (= 0 (:quantity absent)))
      (is (= "{\"x\":null}" (:literal absent)))
      (is (= {:same false "same" true} (get-in outputs [:packet :decisions])))
      (is (= {:same "K" "same" 0} (:identity absent)))
      (is (= 1 (count @requests)))
      (is (not (contains? request :response_format)))
      (doseq [meaning ["Full source packet without repair." "Original source record; preserve presence independently."
                       "Absent, explicit null, or supplied annotation." "Literal text, even when it looks like JSON."
                       "Two independent typed source decisions." "Exact decision key identities."
                       "Keyword decision identity." "String decision identity."]]
        (is (str/includes? (pr-str request) meaning))))))

(deftest paired-function-missing-automatic-call-is-not-reported-as-forced
  (llm/register-provider! :paired-auto-missing
    {:provider :openrouter :model "controlled/model" :output-mode :function-calling
     :config {:api-key "synthetic-not-sent"}})
  (let [captured (atom [])
        failure (with-redefs [http/post
                             (fn [_ options]
                               (swap! captured conj (json/parse-string (:body options) true))
                               (future {:status 200
                                        :body {:id "auto-no-call" :model "controlled/resolved"
                                               :choices [{:finish_reason "stop"
                                                          :message {:role "assistant" :content "plain text is not a response call"}}]}}))]
                  (try (llm/predict :paired-auto-missing
                                   {:instructions "Copy the input."
                                    :inputs [{:name :source :spec :string}]
                                    :outputs [{:name :answer :spec :string}]}
                                   {:source "original"}
                                   {:max-retries 0 :with-provider-evidence? true})
                       nil
                       (catch clojure.lang.ExceptionInfo error error)))]
    (is (= 1 (count @captured)))
    (is (= "auto" (:tool_choice (first @captured))))
    (is (= :tool-call-parsing-failed (:failure-kind (ex-data failure))))
    (is (= false (get-in (ex-data failure) [:provider-evidence :tool-call-present?])))
    (is (= "auto-no-call" (get-in (ex-data failure) [:provider-evidence :response-id])))
    (is (not (str/includes? (.getMessage failure) "Forced")))))
