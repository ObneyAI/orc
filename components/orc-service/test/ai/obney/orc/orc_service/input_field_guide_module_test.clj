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
            [ai.obney.orc.orc-service.core.logic :as service-logic]))

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

(defn- module [] (dissoc (service-logic/build-module node blackboard) :output-mapping))

(defn- inputs [] (service-logic/gather-inputs node blackboard))

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

(defn- guide-sections [text]
  (let [[shown rest-of-request] (str/split text #"\n\nDECLARED CONTRACT \(not supplied evidence\):\n" 2)
        [declared remaining] (when rest-of-request (str/split rest-of-request #"\n\n" 2))]
    [shown declared remaining]))

(defn- check-omitted-input-fields [mode]
  (let [[shown declared remaining] (guide-sections (text-of (request mode)))]
    (is (str/includes? shown "Your input fields are:"))
    (doseq [field fields-no-value-carries]
      (is (not (str/includes? shown field)) (str "Shown inputs omit " field))
      (is (and declared (str/includes? declared field)) (str "The declared shape includes " field))
      (is (and remaining (not (str/includes? remaining field))) (str "The request values omit " field)))))

(deftest a-function-request-keeps-omitted-input-fields-in-the-declared-contract
  (check-omitted-input-fields :function))

(deftest a-shown-field-keeps-its-meaning-and-an-undescribed-one-is-listed-by-name-and-type
  (let [text (text-of (request :function))
        [shown] (guide-sections text)]
    (doseq [meaning ["The ticket subject line"
                     "Promo code the customer quoted"
                     "The customer account"
                     "Support tier"
                     "The conversation so far, oldest first"
                     "One message"
                     "human means a staff member, never the customer's own words"
                     "What was written"]]
      (is (str/includes? shown meaning) (str "carries: " meaning)))
    (testing "an input with no declared meaning is listed by name and type, with no generated text"
      (is (str/includes? shown "`order-id` (str)\n"))
      (is (str/includes? shown "`attachments` (json object)\n"))
      (is (not (str/includes? text "Blackboard key"))))
    (testing "a description is the declared meaning, not the meaning plus the declared type"
      (is (not (str/includes? text "object with")))
      (is (not (str/includes? text "list of")))
      (is (not (str/includes? text "(string)"))))))


(def ^:private outputs-before
  (clojure.edn/read-string
   "[{:name :reply, :orc/strict-description \"The reply to send\", :original-key :reply, :nested-key nil, :spec [:string {:description \"The reply to send\"}], :description \"The reply to send (string)\"} {:name :priority, :orc/strict-description \"How urgent\", :original-key :plan, :nested-key \"priority\", :original-nested-key :priority, :string-key? false, :spec [:enum \"low\" \"high\"], :optional false, :description \"How urgent (one of: low, high)\"} {:name :steps, :orc/strict-description \"The steps, in order\", :original-key :plan, :nested-key \"steps\", :original-nested-key :steps, :string-key? false, :spec [:vector [:map [:action {:description \"What to do\"} :string] [:note {:optional true, :description \"A note for the step\"} :string]]], :optional false, :description \"The steps, in order (list of object with {action: string, note (optional): string})\"} {:name :aside, :orc/strict-description \"An optional aside\", :original-key :plan, :nested-key \"aside\", :original-nested-key :aside, :string-key? false, :spec :string, :optional true, :description \"An optional aside (string)\"}]"))

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
  (let [text (text-of (request :marker))
        [shown] (guide-sections text)
        input-section (-> text
                          (str/split #"Your output fields are:" 2)
                          first)]
    (is (str/starts-with? input-section "Your input fields are:"))
    (doseq [meaning ["The ticket subject line"
                     "The customer account"
                     "Support tier"
                     "human means a staff member, never the customer's own words"]]
      (is (str/includes? shown meaning) (str "carries: " meaning)))
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

(deftest a-marker-request-keeps-omitted-input-fields-in-the-declared-contract
  (check-omitted-input-fields :marker))
