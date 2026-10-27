(ns ai.obney.orc.llm.function-request-guide-test
  "EveryRequestCarriesItsInputFieldGuide, function mode.

  A function request carries, between the node instruction and the input
  values, the input field guide: the name, type and declared meaning of every
  field the request shows. These tests drive the public `predict`, capture the
  request the provider completion receives, and assert what reaches the model.
  Input values are built exactly as the executor sends them: a map or vector is
  JSON text, a nil is the empty string (`executor/serialize-for-llm`)."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hato.client :as http]
            [litellm.router :as router]
            [ai.obney.orc.llm.interface :as llm]))

(defn- as-the-executor-sends
  "`executor/serialize-for-llm`, which the llm brick cannot require."
  [value]
  (cond
    (nil? value) ""
    (map? value) (json/generate-string value)
    (vector? value) (json/generate-string value)
    (coll? value) (json/generate-string (vec value))
    :else value))

(def ^:private support
  {:instructions "Draft the reply to send to the customer."
   :inputs [{:name :subject :spec :string :description "The ticket subject line"}
            {:name :coupon :spec :string :optional true
             :description "Promo code the customer quoted"}
            {:name :account
             :description "The customer account"
             :spec [:map
                    [:tier {:description "Support tier"} [:enum "free" "pro"]]
                    [:region {:optional true :description "Where they are based"} :string]]}
            {:name :messages
             :description "The conversation so far, oldest first"
             :spec [:vector
                    [:map {:description "One message"}
                     [:author-kind {:description "human means a staff member, never the customer's own words"}
                      [:enum "human" "customer"]]
                     [:text {:description "What was written"} :string]
                     [:sent-at {:optional true :description "When it was sent"} :string]]]}]
   :outputs [{:name :reply :spec :string :description "The reply to send"}]})

;; `coupon` is not carried, `account` has no `region`: both are absent fields.
(def ^:private support-values
  {:subject (as-the-executor-sends "Refund please")
   :account (as-the-executor-sends {:tier "pro"})
   :messages (as-the-executor-sends
              [{:author-kind "human" :text "How can I help?"}
               {:author-kind "customer" :text "I was charged twice" :sent-at "2026-01-02"}])})

(def ^:private shown-meanings
  ["The ticket subject line"
   "The customer account"
   "Support tier"
   "The conversation so far, oldest first"
   "One message"
   "human means a staff member, never the customer's own words"
   "What was written"
   "When it was sent"])

(def ^:private absent-meanings
  ["Promo code the customer quoted"
   "Where they are based"])

(defn- capture-request
  "The request `predict` hands the provider, in function mode."
  [spec values options]
  (let [captured (atom nil)]
    (with-redefs [router/supports-function-calling? (constantly true)
                  router/completion
                  (fn [_provider request]
                    (reset! captured request)
                    {:choices [{:message {:tool-calls
                                          [{:function {:name "submit_response"
                                                       :arguments "{\"reply\":\"ok\"}"}}]}}]})]
      (llm/predict :test spec values (merge {:validate? false} options)))
    @captured))

(defn- user-message [request]
  (get-in request [:messages 0 :content]))

(deftest a-function-request-carries-every-shown-fields-meaning-between-instruction-and-values
  (doseq [options [{} {:force-tool-choice? true}]]
    (testing (str "options " (pr-str options))
      (let [content (user-message (capture-request support support-values options))
            at #(str/index-of content %)
            instruction-at (at "Draft the reply to send to the customer.")
            values-at (at "Given the following inputs:")]
        (is (= 0 instruction-at) "the instruction opens the message")
        (is (some? values-at))
        (doseq [meaning shown-meanings]
          (is (some? (at meaning)) (str "carries: " meaning))
          (when-let [meaning-at (at meaning)]
            (is (< instruction-at meaning-at values-at)
                (str "after the instruction and before the values: " meaning))))
        (doseq [meaning absent-meanings]
          (is (nil? (at meaning)) (str "does not describe an absent field: " meaning)))))))

(deftest an-absent-input-is-named-nowhere-in-a-function-request
  (doseq [options [{} {:force-tool-choice? true}]]
    (testing (str "options " (pr-str options))
      (let [content (user-message (capture-request support support-values options))]
        (is (not (str/includes? content "coupon"))
            "neither listed among the values nor described in the guide")
        (is (not (str/includes? content "region"))
            "an absent nested field is not named either")
        (testing "the shown inputs are still given as name: value lines"
          (is (str/includes? content "\nsubject: Refund please\n"))
          (is (str/includes? content "\naccount: {\"tier\":\"pro\"}\n")))))))

;; ---------------------------------------------------------------------------
;; The tool definition is not part of this change. The golden below is the
;; `tools` JSON that function mode produced for this contract BEFORE the input
;; field guide existed; it must stay byte-for-byte what a provider receives
;; (so a provider's prompt cache and a consumer's tool schema are untouched).
;; ---------------------------------------------------------------------------

(def ^:private triage
  {:instructions "Triage the ticket."
   :inputs [{:name :subject :spec :string :description "Subject"}]
   :outputs [{:name :reply :spec :string :description "The reply to send"}
             {:name :priority :spec [:enum "low" "high"] :description "How urgent"}
             {:name :aside :spec :string :optional true :description "An optional aside"}
             {:name :plan :description "The plan"
              :spec [:map
                     [:steps [:vector [:map [:action :string]
                                       [:note {:optional true} :string]]]]]}
             {:name :weights :spec [:map-of :string :int] :description "Weights"}]})

(def ^:private triage-tools-before-the-guide
  "[{\"type\":\"function\",\"function\":{\"name\":\"submit_response\",\"description\":\"Triage the ticket.\",\"parameters\":{\"type\":\"object\",\"properties\":{\"reply\":{\"type\":\"string\",\"description\":\"The reply to send\"},\"priority\":{\"type\":\"string\",\"enum\":[\"low\",\"high\"],\"description\":\"How urgent\"},\"aside\":{\"oneOf\":[{\"type\":\"string\"},{\"type\":\"null\"}],\"description\":\"An optional aside\"},\"plan\":{\"type\":\"object\",\"properties\":{\"steps\":{\"type\":\"array\",\"items\":{\"type\":\"object\",\"properties\":{\"action\":{\"type\":\"string\"},\"note\":{\"oneOf\":[{\"type\":\"string\"},{\"type\":\"null\"}]}},\"required\":[\"action\"]}}},\"required\":[\"steps\"],\"description\":\"The plan\"},\"weights\":{\"type\":\"object\",\"additionalProperties\":{\"type\":\"integer\"},\"description\":\"Weights\"}},\"required\":[\"reply\",\"priority\",\"plan\",\"weights\"]}}}]")

(deftest the-tool-definition-is-byte-identical-to-before-the-guide
  (doseq [options [{} {:force-tool-choice? true}]]
    (testing (str "options " (pr-str options))
      (let [request (capture-request triage {:subject "Refund please"} options)]
        (is (= triage-tools-before-the-guide (json/generate-string (:tools request))))
        (is (not (str/includes? (json/generate-string (:tools request)) "Your input fields are"))
            "the guide rides in the message, never in the tool")))))

;; ---------------------------------------------------------------------------
;; Wire: the body the pinned OpenRouter adapter serializes and would POST.
;; ---------------------------------------------------------------------------

(defn- capture-wire-body
  "Run `predict` against the pinned OpenRouter adapter with the HTTP post
  intercepted; returns the parsed JSON body that would be sent."
  [spec values options]
  (let [config-name (keyword (str "ifg5-wire-" (random-uuid)))
        captured (atom nil)]
    (router/register!
     config-name
     {:provider :openrouter
      :model "test/model"
      :config {:api-key "not-sent"
               :api-base "https://transport.invalid"}})
    (try
      (with-redefs
        [http/post
         (fn [url request]
           (reset! captured {:url url :request request})
           (future
             {:status 200
              :body {:id "ifg5-response"
                     :model "test/model"
                     :choices [{:index 0
                                :message {:role "assistant"
                                          :tool_calls [{:id "call-1"
                                                        :type "function"
                                                        :function {:name "submit_response"
                                                                   :arguments "{\"reply\":\"ok\"}"}}]}
                                :finish_reason "tool_calls"}]
                     :usage {:prompt_tokens 1 :completion_tokens 1 :total_tokens 2}}}))]
        (llm/predict config-name spec values
                     (merge {:validate? false :use-function-calling? true} options))
        (let [{:keys [url request]} @captured
              body (:body request)]
          {:url url
           :raw body
           :body (when (string? body) (json/parse-string body))}))
      (finally
        (router/unregister! config-name)))))

(deftest the-serialized-function-request-body-carries-the-guide
  (doseq [options [{} {:force-tool-choice? true}]]
    (testing (str "options " (pr-str options))
      (let [{:keys [url raw body]} (capture-wire-body support support-values options)
            content (get-in body ["messages" 0 "content"])
            at #(str/index-of content %)]
        (is (= "https://transport.invalid/chat/completions" url))
        (is (string? raw) "the adapter serialized the body to JSON text")
        (is (string? content))
        (is (= 0 (at "Draft the reply to send to the customer.")))
        (doseq [meaning shown-meanings]
          (is (some? (at meaning)) (str "the wire body carries: " meaning)))
        (doseq [meaning absent-meanings]
          (is (nil? (at meaning)) (str "the wire body omits: " meaning)))
        (is (not (str/includes? content "coupon")))
        (is (some? (at "Your input fields are:")) "the guide is in the body")
        (is (some? (at "Given the following inputs:")))
        (when (and (at "Your input fields are:") (at "Given the following inputs:"))
          (is (< (at "Your input fields are:") (at "Given the following inputs:"))
              "the guide comes before the values"))
        (is (= "submit_response" (get-in body ["tools" 0 "function" "name"]))
            "the function-calling shape is what was serialized")))))

;; ---------------------------------------------------------------------------
;; Shown, edge by edge.
;; ---------------------------------------------------------------------------

(deftest an-input-present-with-no-value-is-shown
  ;; The executor sends a nil blackboard value as the empty string; a direct
  ;; caller may send nil itself. Either way the key is present: the model is
  ;; shown the field, so it is explained and listed.
  (doseq [no-value ["" nil]]
    (testing (str "value " (pr-str no-value))
      (let [content (user-message
                     (capture-request support (assoc support-values :coupon no-value) {}))]
        (is (str/includes? content "Promo code the customer quoted")
            "present with no value is explained")
        (is (str/includes? content "\ncoupon: \n")
            "and listed, with its empty value")))))

(def ^:private describe-photo
  {:instructions "Describe the damage."
   :inputs [{:name :photo :type :image :spec :string :description "A photo of the damage"}
            {:name :caption :spec :string :description "What the customer wrote"}]
   :outputs [{:name :text :spec :string :description "The description"}]})

(deftest an-image-input-stays-a-content-part-and-is-not-in-the-guide
  (let [content (user-message
                 (capture-request describe-photo
                                  {:photo "data:image/png;base64,AAA" :caption "Cracked"}
                                  {}))
        [text-part image-part] content
        text (:text text-part)]
    (is (= 2 (count content)))
    (is (= {:type "image_url" :image_url {:url "data:image/png;base64,AAA"}} image-part))
    (is (str/includes? text "What the customer wrote")
        "the text input is explained")
    (is (not (str/includes? text "A photo of the damage"))
        "the image is not described in the text")
    (is (not (str/includes? text "photo"))
        "nor named in it")))

(deftest a-request-showing-no-text-input-has-no-guide-and-is-otherwise-unchanged
  (let [spec {:instructions "Do it."
              :inputs []
              :outputs [{:name :n :spec :int :description "A number"}]}]
    (is (= (str "Do it.\n\nGiven the following inputs:\n\n\n"
                "Call the submit_response function with your answer.")
           (user-message (capture-request spec {} {})))
        "exactly the message function mode built before the guide existed")))

(deftest without-an-instruction-the-guide-opens-the-message
  (let [spec {:inputs [{:name :q :spec :string :description "The question"}]
              :outputs [{:name :n :spec :int :description "A number"}]}]
    (is (= (str "Your input fields are:\n"
                "1. `q` (str): The question\n\n"
                "Given the following inputs:\nq: why\n\n"
                "Call the submit_response function with your answer.")
           (user-message (capture-request spec {:q "why"} {}))))))

;; ---------------------------------------------------------------------------
;; Opt-in prompt caching and generic tool description. Off by default.
;; ---------------------------------------------------------------------------

(deftest the-opt-in-controls-leave-a-default-request-untouched
  (let [plain (capture-request triage {:subject "Refund please"} {})
        off (capture-request triage {:subject "Refund please"}
                             {:prompt-cache? false :generic-tool-description? false})]
    (is (= plain off) "explicitly off equals absent")
    (is (string? (user-message plain)) "content is the single prompt string")
    (is (not (str/includes? (json/generate-string plain) "cache_control")))
    (is (not (contains? plain :prompt-cache?)) "the option never reaches the provider")
    (is (not (contains? plain :generic-tool-description?)))
    (is (= triage-tools-before-the-guide (json/generate-string (:tools plain))))))

(deftest prompt-cache-puts-the-instruction-first-in-its-own-marked-part
  (let [plain (capture-request triage {:subject "Refund please"} {})
        cached (capture-request triage {:subject "Refund please"} {:prompt-cache? true})
        [head tail & more] (user-message cached)]
    (is (nil? more))
    (is (= {:type "text" :text "Triage the ticket." :cache_control {:type "ephemeral"}} head))
    (is (= {:type "text"} (dissoc tail :text)) "second part is unmarked text")
    (is (= (user-message plain) (str (:text head) "\n\n" (:text tail)))
        "the same words in the same order")
    (is (= {:type "ephemeral"} (get-in cached [:tools 0 :cache_control])))
    (is (= (get-in plain [:tools 0 :function])
           (get-in cached [:tools 0 :function])) "the tool function itself is unchanged")
    (is (not (contains? cached :prompt-cache?)))))

(deftest the-cached-prefix-is-the-same-across-different-inputs
  (let [a (capture-request triage {:subject "Refund please"} {:prompt-cache? true})
        b (capture-request triage {:subject "Where is my parcel"} {:prompt-cache? true})]
    (is (= (first (user-message a)) (first (user-message b))))
    (is (= (:tools a) (:tools b)))
    (is (not= (second (user-message a)) (second (user-message b))))))

(deftest generic-tool-description-stops-the-instruction-being-sent-twice
  (let [plain (capture-request triage {:subject "x"} {})
        generic (capture-request triage {:subject "x"} {:generic-tool-description? true})]
    (is (= "Submit the structured response" (get-in generic [:tools 0 :function :description])))
    (is (= (get-in plain [:tools 0 :function :parameters])
           (get-in generic [:tools 0 :function :parameters])))
    (is (= (user-message plain) (user-message generic)) "the message still carries the instruction")
    (is (not (str/includes? (json/generate-string (:tools generic)) "Triage the ticket.")))))

(deftest prompt-cache-without-an-instruction-keeps-a-single-string
  (let [bare (dissoc triage :instructions)
        cached (capture-request bare {:subject "x"} {:prompt-cache? true})]
    (is (string? (user-message cached)))))

(deftest prompt-cache-keeps-image-parts-after-the-text
  (let [spec {:instructions "Describe it."
              :inputs [{:name :photo :type :image :spec :string}]
              :outputs [{:name :caption :spec :string}]}
        content (user-message (capture-request spec {:photo "data:image/png;base64,AAAA"}
                                               {:prompt-cache? true}))]
    (is (= ["text" "text" "image_url"] (mapv :type content)))
    (is (= {:type "ephemeral"} (:cache_control (first content))))))
