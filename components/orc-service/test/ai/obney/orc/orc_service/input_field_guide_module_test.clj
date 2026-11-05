(ns ai.obney.orc.orc-service.input-field-guide-module-test
  "EveryRequestCarriesItsInputFieldGuide, through ORC's own path.

  A leaf's module comes from `executor/build-module`, its values from
  `executor/gather-inputs`, and the request from the public `llm/predict` with
  only the provider completion intercepted. An input field's description in the
  module is its declared meaning and nothing else: the input field guide states
  each shown field's name and type itself, so a description that also spelled
  out the declared type would name nested fields the request does not carry."
  (:require [cheshire.core :as json]
            [clojure.edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [litellm.router :as router]
            [ai.obney.orc.llm.interface :as llm]
            [ai.obney.orc.orc-service.core.executor :as executor]))

;; The blackboard a workflow would hold. Every input is described except
;; `:order-id` and `:attachments`. Fields no value carries: `region` (account),
;; `sent-at` (messages), `internal-memo` (attachments).
(def ^:private blackboard
  {:subject {:schema [:string {:description "The ticket subject line"}]
             :value "Refund please"}
   :coupon {:schema [:string {:description "Promo code the customer quoted"}]
            :value nil}
   :order-id {:schema :string
              :value "A-17"}
   :account {:schema [:map {:description "The customer account"}
                      [:tier {:description "Support tier"} [:enum "free" "pro"]]
                      [:region {:optional true :description "Where they are based"} :string]]
             :value {:tier "pro"}}
   :messages {:schema [:vector {:description "The conversation so far, oldest first"}
                       [:map {:description "One message"}
                        [:author-kind {:description "human means a staff member, never the customer's own words"}
                         [:enum "human" "customer"]]
                        [:text {:description "What was written"} :string]
                        [:sent-at {:optional true :description "When it was sent"} :string]]]
              :value [{:author-kind "human" :text "How can I help?"}
                      {:author-kind "customer" :text "I was charged twice"}]}
   :attachments {:schema [:map
                          [:kind :string]
                          [:internal-memo {:optional true} :string]]
                 :value {:kind "pdf"}}
   ;; Outputs, including nested ones the executor flattens.
   :reply {:schema [:string {:description "The reply to send"}]}
   :plan {:schema [:map {:description "What to do next"}
                   [:priority {:description "How urgent"} [:enum "low" "high"]]
                   [:steps {:description "The steps, in order"}
                    [:vector [:map [:action {:description "What to do"} :string]
                              [:note {:optional true :description "A note for the step"} :string]]]]
                   [:aside {:optional true :description "An optional aside"} :string]]}})

(def ^:private node
  {:name "draft"
   :instruction "Draft the reply to send to the customer."
   :reads [:subject :coupon :order-id :account :messages :attachments]
   :writes [:reply :plan]})

(defn- module [] (dissoc (executor/build-module node blackboard) :output-mapping))

(defn- inputs [] (executor/gather-inputs node blackboard))

(defn- request
  "The request `llm/predict` hands the provider for the leaf, in `mode`."
  [mode]
  (let [captured (atom nil)]
    (with-redefs [router/supports-function-calling? (constantly (= mode :function))
                  router/completion
                  (fn [_provider request]
                    (reset! captured request)
                    {:choices [{:message {:content "[[ ## reply ## ]]\nok"
                                          :tool-calls [{:function {:name "submit_response"
                                                                   :arguments "{\"reply\":\"ok\"}"}}]}}]})]
      (llm/predict :test (module) (inputs)
                   {:validate? false :use-function-calling? (= mode :function)}))
    @captured))

(defn- text-of [request]
  (let [content (get-in request [:messages 0 :content])]
    (if (string? content) content (:text (first content)))))

(def ^:private fields-no-value-carries
  ["region" "sent-at" "internal-memo"])

(deftest a-function-request-through-the-executor-names-no-field-the-values-lack
  (let [text (text-of (request :function))]
    (testing "the guide is there"
      (is (str/includes? text "Your input fields are:")))
    (doseq [absent fields-no-value-carries]
      (is (not (str/includes? text absent))
          (str "an absent nested field is named nowhere: " absent)))))

(deftest a-shown-field-keeps-its-meaning-and-an-undescribed-one-is-listed-by-name-and-type
  (let [text (text-of (request :function))]
    (doseq [meaning ["The ticket subject line"
                     "Promo code the customer quoted"
                     "The customer account"
                     "Support tier"
                     "The conversation so far, oldest first"
                     "One message"
                     "human means a staff member, never the customer's own words"
                     "What was written"]]
      (is (str/includes? text meaning) (str "carries: " meaning)))
    (testing "an input with no declared meaning is listed by name and type, with no generated text"
      (is (str/includes? text "`order-id` (str)\n"))
      (is (str/includes? text "`attachments` (json object)\n"))
      (is (not (str/includes? text "Blackboard key"))))
    (testing "a description is the declared meaning, not the meaning plus the declared type"
      (is (not (str/includes? text "object with")))
      (is (not (str/includes? text "list of")))
      (is (not (str/includes? text "(string)"))))))

;; ---------------------------------------------------------------------------
;; Outputs are not part of this change. The goldens below are what the module
;; and the function request produced for this contract BEFORE input descriptions
;; stopped carrying type text: the flattened output fields with their
;; descriptions, and the submit_response tool a provider receives. The merged
;; judge runtime also retains whether each flattened key was declared a string.
;; ---------------------------------------------------------------------------

(def ^:private outputs-before
  (clojure.edn/read-string
   "[{:name :reply, :original-key :reply, :nested-key nil, :spec [:string {:description \"The reply to send\"}], :description \"The reply to send (string)\"} {:name :priority, :original-key :plan, :nested-key \"priority\", :string-key? false, :spec [:enum \"low\" \"high\"], :optional false, :description \"How urgent (one of: low, high)\"} {:name :steps, :original-key :plan, :nested-key \"steps\", :string-key? false, :spec [:vector [:map [:action {:description \"What to do\"} :string] [:note {:optional true, :description \"A note for the step\"} :string]]], :optional false, :description \"The steps, in order (list of object with {action: string, note (optional): string})\"} {:name :aside, :original-key :plan, :nested-key \"aside\", :string-key? false, :spec :string, :optional true, :description \"An optional aside (string)\"}]"))

(def ^:private tools-before
  "[{\"type\":\"function\",\"function\":{\"name\":\"submit_response\",\"description\":\"Draft the reply to send to the customer.\",\"parameters\":{\"type\":\"object\",\"properties\":{\"reply\":{\"description\":\"The reply to send (string)\",\"type\":\"string\"},\"priority\":{\"type\":\"string\",\"enum\":[\"low\",\"high\"],\"description\":\"How urgent (one of: low, high)\"},\"steps\":{\"type\":\"array\",\"items\":{\"type\":\"object\",\"properties\":{\"action\":{\"description\":\"What to do\",\"type\":\"string\"},\"note\":{\"description\":\"A note for the step\",\"oneOf\":[{\"type\":\"string\"},{\"type\":\"null\"}]}},\"required\":[\"action\"]},\"description\":\"The steps, in order (list of object with {action: string, note (optional): string})\"},\"aside\":{\"oneOf\":[{\"type\":\"string\"},{\"type\":\"null\"}],\"description\":\"An optional aside (string)\"}},\"required\":[\"reply\",\"priority\",\"steps\"]}}}]")

(deftest output-descriptions-and-the-tool-definition-are-byte-identical-to-before
  (is (= outputs-before (:outputs (module)))
      "the flattened output fields, descriptions included")
  (is (= ["The reply to send (string)"
          "How urgent (one of: low, high)"
          "The steps, in order (list of object with {action: string, note (optional): string})"
          "An optional aside (string)"]
         (mapv :description (:outputs (module)))))
  (is (= tools-before (json/generate-string (:tools (request :function))))
      "the tool a function-mode provider receives"))

(deftest a-marker-request-through-the-executor-describes-inputs-by-their-declared-meaning-only
  ;; The input section is whatever sits between the two section headings. The
  ;; output section keeps its type text (outputs are unchanged), so the input
  ;; section is checked on its own.
  (let [text (text-of (request :marker))
        input-section (-> text
                          (str/split #"Your output fields are:" 2)
                          first)]
    (is (str/starts-with? input-section "Your input fields are:"))
    (doseq [meaning ["The ticket subject line"
                     "The customer account"
                     "Support tier"
                     "human means a staff member, never the customer's own words"]]
      (is (str/includes? input-section meaning) (str "carries: " meaning)))
    (is (not (str/includes? input-section "Blackboard key"))
        "no generated text for an undescribed input")
    (is (not (str/includes? input-section "object with"))
        "no declared type spelled out in a description")
    (is (not (str/includes? input-section "list of")))
    (is (not (str/includes? input-section "(string)")))))

(deftest an-input-fields-description-in-the-module-is-its-declared-meaning-only
  (let [fields (:inputs (module))]
    (is (= [:subject :coupon :order-id :account :messages :attachments]
           (mapv :name fields)))
    (is (= ["The ticket subject line"
            "Promo code the customer quoted"
            nil
            "The customer account"
            "The conversation so far, oldest first"
            nil]
           (mapv :description fields))
        "the declared meaning, exactly: no type text, no generated text")
    (testing "an undeclared meaning is an absent description, not an empty or generated one"
      (is (every? #(not (contains? % :description))
                  (filter (comp #{:order-id :attachments} :name) fields))))))

(deftest a-marker-request-through-the-executor-names-no-field-the-values-lack
  (let [text (text-of (request :marker))]
    (is (str/includes? text "Your input fields are:"))
    (doseq [absent fields-no-value-carries]
      (is (not (str/includes? text absent))
          (str "an absent nested field is named nowhere: " absent)))))
