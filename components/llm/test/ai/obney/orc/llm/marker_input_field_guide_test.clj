(ns ai.obney.orc.llm.marker-input-field-guide-test
  "Marker and streaming requests carry the input field guide.

  Every test drives the public `predict` / `predict-stream-v2` with the provider
  boundary intercepted, and asserts on what the provider would receive: the
  request ORC built, or the serialized HTTP body. Input values are built the way
  the executor builds them (`serialize-for-llm`): complex values as JSON text,
  nil as an empty string."
  (:require [cheshire.core :as json]
            [clojure.core.async :as async]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hato.client :as http]
            [litellm.router :as router]
            [ai.obney.orc.llm.interface :as llm]))

;; --------------------------------------------------------------------------- ;;
;; A contract whose meaning lives at every level: top-level, nested, and in the
;; item fields of a collection. Optional fields exist at each of those levels so
;; that a request can leave them out.
;; --------------------------------------------------------------------------- ;;

(def ^:private support
  {:inputs
   [{:name :subject
     :spec :string
     :description "The ticket subject line"}
    {:name :promo-code
     :spec :string
     :optional true
     :description "Promo code the customer quoted"}
    {:name :order-id
     :spec :string
     :description "The order the ticket is about"}
    {:name :account
     :description "The customer account"
     :spec [:map
            [:tier {:description "Support tier"} [:enum "free" "pro"]]
            [:billing-region {:optional true :description "Where they are billed"} :string]]}
    {:name :messages
     :description "The conversation so far, oldest first"
     :spec [:vector
            [:map {:description "One message"}
             [:author-kind {:description "human means a staff member, never the customer's own words"}
              [:enum "human" "customer"]]
             [:text {:description "What was written"} :string]
             [:sent-at {:optional true :description "When it was sent"} :string]]]}]
   :outputs [{:name :reply :spec :string :description "The reply to send"}]
   :instructions "Reply to the customer."})

;; Mirrors `executor/serialize-for-llm`, the function that builds every value
;; `predict` receives from the executor: nil -> "", maps and collections -> JSON
;; text, scalars as they are.
(defn- serialize-for-llm [value]
  (cond
    (nil? value) ""
    (map? value) (json/generate-string value)
    (vector? value) (json/generate-string value)
    (coll? value) (json/generate-string (vec value))
    :else value))

(defn- executor-values
  "The values `predict` receives for `raw`, an input-name -> value map: only the
  inputs present in `raw`, each serialized the way the executor serializes it."
  [raw]
  (update-vals raw serialize-for-llm))

(def ^:private reply-response
  {:choices [{:message {:content "[[ ## reply ## ]]\nOn it."}}]})

(defn- request-text
  "Everything the provider is sent in the messages, as one string."
  [request]
  (->> (:messages request)
       (map (fn [{:keys [content]}]
              (if (string? content)
                content
                (str/join "\n" (keep :text content)))))
       (str/join "\n")))

(defn- marker-request-for
  "The request `predict` builds in marker mode, captured at the provider
  completion."
  [spec raw-values]
  (let [captured (atom nil)]
    (with-redefs [router/completion (fn [_provider request]
                                      (reset! captured request)
                                      reply-response)]
      (llm/predict :test spec (executor-values raw-values)
                   {:validate? false :use-function-calling? false}))
    @captured))

(def ^:private shown-meanings
  ["The ticket subject line"
   "The customer account"
   "Support tier"
   "The conversation so far, oldest first"
   "One message"
   "human means a staff member, never the customer's own words"
   "What was written"])

(def ^:private absent-nested-names-and-meanings
  ["billing-region" "Where they are billed"
   "sent-at" "When it was sent"])

(def ^:private conversation
  [{:author-kind "human" :text "How can I help?"}
   {:author-kind "customer" :text "I was charged twice"}])

(defn- check-guide
  "Asserts the shown meanings reach the provider (`text`, the message the model
  reads) and the nested absences do not appear anywhere in what is sent
  (`everything`)."
  [text everything]
  (testing "top-level, nested and collection-item meanings are all in the request"
    (doseq [meaning shown-meanings]
      (is (str/includes? text meaning) (str "missing: " meaning))))
  (testing "a nested or item field this request does not carry is neither named nor explained"
    (doseq [absent absent-nested-names-and-meanings]
      (is (not (str/includes? everything absent)) (str "present: " absent)))))

(defn- check-guide-in-request [request]
  (check-guide (request-text request) (pr-str request)))

(def ^:private shown-values
  {:subject "Refund please"
   :account {:tier "pro"}
   :messages conversation})

(deftest a-marker-request-carries-every-shown-fields-meaning-and-no-absent-fields
  (check-guide-in-request (marker-request-for support shown-values)))

(defn- streamed-request-for
  "The request `predict-stream-v2` builds, captured at the provider completion.
  The stream is drained so nothing is left running."
  [spec raw-values]
  (let [captured (atom nil)
        chunks [{:choices [{:delta {:content "[[ ## reply ## ]]\nOn it."}}]}]]
    (with-redefs [router/completion
                  (fn [_provider request]
                    (reset! captured request)
                    (let [ch (async/chan 1)]
                      (async/onto-chan! ch chunks)
                      ch))]
      (let [events (async/<!! (async/into [] (llm/predict-stream-v2
                                              :test spec (executor-values raw-values)
                                              {:validate? false :debounce-ms 0})))]
        (is (= :final (:orc/event (last events))))))
    @captured))

(deftest a-streamed-request-carries-every-shown-fields-meaning-and-no-absent-fields
  (let [request (streamed-request-for support shown-values)]
    (is (true? (:stream request)) "the request is a streaming one")
    (check-guide-in-request request)))

;; --------------------------------------------------------------------------- ;;
;; The wire: the pinned OpenRouter adapter, with only the HTTP post intercepted.
;; --------------------------------------------------------------------------- ;;

(defn- wire-body-for
  "The serialized HTTP body `predict` sends through the pinned OpenRouter
  adapter in marker mode, captured at the HTTP post."
  [spec raw-values]
  (let [config-name (keyword (str "ifg-marker-wire-" (random-uuid)))
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
         (fn [url options]
           (reset! captured {:url url :options options})
           (future
             {:status 200
              :body {:id "ifg-response"
                     :model "test/model"
                     :choices [{:index 0
                                :message {:role "assistant"
                                          :content "[[ ## reply ## ]]\nOn it."}
                                :finish_reason "stop"}]
                     :usage {:prompt_tokens 1
                             :completion_tokens 1
                             :total_tokens 2}}}))]
        (is (= {:reply "On it."}
               (llm/predict config-name spec (executor-values raw-values)
                            {:validate? false :use-function-calling? false})))
        (is (= "https://transport.invalid/chat/completions" (:url @captured))))
      (finally
        (router/unregister! config-name)))
    (get-in @captured [:options :body])))

(deftest the-serialized-wire-body-carries-the-guide-in-marker-mode
  (let [body (wire-body-for support shown-values)
        message (-> (json/parse-string body true) :messages first :content)]
    (is (string? body) "the body is the serialized JSON the provider receives")
    (check-guide message body)))

;; --------------------------------------------------------------------------- ;;
;; An input the request does not carry is named nowhere in what is sent -- not in
;; the guide, not in the interaction markers, not in the input values.
;; --------------------------------------------------------------------------- ;;

(def ^:private top-level-names-and-meanings
  {:promo-code ["promo-code" "Promo code the customer quoted"]
   :order-id ["order-id" "The order the ticket is about"]})

(def ^:private everything-sent-by
  "Each request path, as the whole of what it sends, as one string."
  {"blocking marker request" (fn [spec values] (pr-str (marker-request-for spec values)))
   "streamed request" (fn [spec values] (pr-str (streamed-request-for spec values)))
   "serialized wire body" wire-body-for})

(deftest an-absent-top-level-input-is-named-nowhere-in-the-request
  (let [all-present (assoc shown-values :promo-code "SPRING10" :order-id "A-1042")]
    (doseq [[path everything-sent] everything-sent-by]
      (testing (str path ": an input the request carries is explained")
        (let [sent (everything-sent support all-present)]
          (doseq [text (mapcat val top-level-names-and-meanings)]
            (is (str/includes? sent text) (str "missing: " text)))))
      (testing (str path ": an input the request does not carry appears nowhere")
        (let [sent (everything-sent support shown-values)]
          (doseq [text (mapcat val top-level-names-and-meanings)]
            (is (not (str/includes? sent text)) (str "present: " text))))))))

;; --------------------------------------------------------------------------- ;;
;; Present with no value is shown: "no value" is read with the meaning declared.
;; --------------------------------------------------------------------------- ;;

(deftest a-field-present-with-no-value-is-shown-at-the-top-level-and-nested
  (let [request (marker-request-for support {:subject "Refund please"
                                             :promo-code nil
                                             :account {:tier "pro" :billing-region nil}
                                             :messages conversation})
        text (request-text request)]
    (is (= "" (:promo-code (executor-values {:promo-code nil}))) "the executor sends nil as an empty string")
    (testing "a top-level input sent as an empty string is named and explained"
      (is (str/includes? text "`promo-code`"))
      (is (str/includes? text "Promo code the customer quoted")))
    (testing "and its value section is there too: the marker opens the interaction
              format and opens the input's value"
      (is (= 2 (count (re-seq #"\[\[ ## promo-code ## \]\]" text)))))
    (testing "a nested field sent as null is explained"
      (is (str/includes? text "Where they are billed")))))

;; --------------------------------------------------------------------------- ;;
;; Image inputs stay content parts; the guide does not list them.
;; --------------------------------------------------------------------------- ;;

(def ^:private with-photo
  (update support :inputs conj
          {:name :damage-photo :type :image :spec :string
           :description "A photo of the damage"}))

(deftest an-image-input-stays-a-content-part-and-is-not-in-the-guide
  (let [photo "data:image/png;base64,iVBORw0KGgo="
        shown (marker-request-for with-photo (assoc shown-values :damage-photo photo))
        absent (marker-request-for with-photo shown-values)]
    (testing "a shown image is sent as an image part beside the text"
      (is (= [{:type "image_url" :image_url {:url photo}}]
             (filter #(= "image_url" (:type %)) (-> shown :messages first :content)))))
    (testing "the image input is not explained or named in the text"
      (is (not (str/includes? (request-text shown) "damage-photo")))
      (is (not (str/includes? (request-text shown) "A photo of the damage"))))
    (testing "the guide is still there beside the image"
      (check-guide (request-text shown) (request-text shown)))
    (testing "an absent image input sends no image part and is named nowhere"
      (is (= ["text"] (mapv :type (-> absent :messages first :content))))
      (is (not (str/includes? (pr-str absent) "damage-photo"))))))
