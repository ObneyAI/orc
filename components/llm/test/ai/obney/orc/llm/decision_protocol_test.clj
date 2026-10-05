(ns ai.obney.orc.llm.decision-protocol-test
  "Generated from contract DecisionProtocolPrediction (specs/llm.allium):
   ProtocolFollowsRegisteredProvider, DecisionModelsAnswerOnlyFiniteQuestions,
   DecisionRequestCarriesDeclaredMeaning, DecisionAnswersAreValidatedNotRepaired,
   DecisionMetadataIsPreserved.

   The decision provider is registered through the public boundary with an
   injected transport (decoded request map in, decoded response map out), so
   the real request construction, validation and metadata mapping run. Response
   shapes follow the OpenRouter /api/alpha/decisions reference:
   noul {:type \"noul\" :noul p}; choice {:type \"choice\" :choice id
   :confidence? c :probabilities? {id p}}; usage {:input_tokens :output_tokens :cost}."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hato.client :as http]
            [litellm.router :as router]
            [ai.obney.orc.llm.interface :as llm]))

(def ^:private route-descriptions
  {"lookup" "Retrieve an existing catalog fact."
   "research" "Investigate a question that needs new evidence."
   "clarify" "The request is too ambiguous to act on."})

(def ^:private route-spec
  {:inputs [{:name :request :spec :string :description "The user's request"}]
   :outputs [{:name :route
              :spec (into [:enum {:descriptions route-descriptions}]
                          (keys route-descriptions))
              :description "The operation that addresses the request"}]
   :instructions "Which operation addresses the request?"})

(def ^:private boolean-spec
  {:inputs [{:name :request :spec :string :description "The user's request"}]
   :outputs [{:name :answerable :spec :boolean
              :description "Whether the catalog can answer the request"}]
   :instructions "Can the request be answered from the catalog?"})

(def ^:private dummy-key "sk-or-test-DUMMY-not-a-real-key")

(defn- confidence-for [probabilities]
  (let [n (count probabilities)
        pmax (apply max (vals probabilities))]
    (/ (- pmax (/ 1.0 n)) (- 1.0 (/ 1.0 n)))))

(defn- response [answers]
  {:id "dec-1"
   :model "typesafe/jev-1.13-20260917"
   :provider "TypeSafe"
   :answers answers
   :usage {:input_tokens 120 :output_tokens 3 :cost 0.0004}})

(def ^:private lookup-probabilities {"lookup" 0.8 "research" 0.15 "clarify" 0.05})

(def ^:private valid-choice
  {:route {:type "choice" :choice "lookup"
           :confidence (confidence-for lookup-probabilities)
           :probabilities lookup-probabilities}})

(defn- register-decision-provider!
  "Register a decision-protocol provider whose transport records each request
   and returns `reply` (a response map, or a fn of the request)."
  [provider-name reply requests]
  (llm/register-provider!
   provider-name
   {:provider :openrouter
    :protocol :decision
    :model "typesafe/jev-1.13"
    :config {:api-key dummy-key
             :decision-transport
             (fn [request]
               (swap! requests conj request)
               (if (fn? reply) (reply request) reply))}}))

(defn- predict-failure [f]
  (try (f) nil
       (catch clojure.lang.ExceptionInfo e e)))

;; ---------------------------------------------------------------------------
;; ProtocolFollowsRegisteredProvider
;; ---------------------------------------------------------------------------

(deftest decision-provider-answers-through-the-ordinary-predict-call
  (let [requests (atom [])]
    (register-decision-provider! :decision-test-choice (response valid-choice) requests)
    (is (= {:route "lookup"}
           (llm/predict :decision-test-choice route-spec {:request "Q3 revenue?"})))
    (is (= 1 (count @requests)))))

(deftest decision-model-id-on-a-chat-provider-stays-conversational
  (let [completions (atom 0)]
    (llm/register-provider! :decision-test-chat
                            {:provider :openrouter
                             :model "typesafe/jev-1.13"
                             :config {:api-key dummy-key}})
    (with-redefs [router/supports-function-calling? (constantly false)
                  router/completion
                  (fn [_provider _request]
                    (swap! completions inc)
                    {:choices [{:message {:content "[[ ## route ## ]]\nlookup"}}]})]
      (llm/predict :decision-test-chat route-spec {:request "Q3 revenue?"}
                   {:validate? false}))
    (is (= 1 @completions) "a chat provider uses the conversational path")))

;; ---------------------------------------------------------------------------
;; DecisionModelsAnswerOnlyFiniteQuestions
;; ---------------------------------------------------------------------------

(deftest non-finite-outputs-are-refused-before-invocation
  (let [requests (atom [])
        spec (update route-spec :outputs conj
                     {:name :summary :spec :string :description "A summary"})]
    (register-decision-provider! :decision-test-finite (response valid-choice) requests)
    (let [e (predict-failure #(llm/predict :decision-test-finite spec {:request "x"}))]
      (is (some? e))
      (is (str/includes? (ex-message e) "summary") "the failure names the undecidable output")
      (is (zero? (count @requests)) "no provider invocation"))))

;; ---------------------------------------------------------------------------
;; DecisionRequestCarriesDeclaredMeaning
;; ---------------------------------------------------------------------------

(deftest choice-request-carries-state-options-and-meaning
  (let [requests (atom [])]
    (register-decision-provider! :decision-test-request (response valid-choice) requests)
    (llm/predict :decision-test-request route-spec {:request "What was Q3 revenue?"})
    (let [{:keys [model state questions]} (first @requests)
          question (get questions :route (get questions "route"))]
      (is (= "typesafe/jev-1.13" model))
      (is (str/includes? (pr-str state) "What was Q3 revenue?"))
      (is (= "choice" (name (:type question))))
      (is (= route-descriptions
             (update-keys (:criteria question) #(if (keyword? %) (name %) %))))
      (is (str/includes? (pr-str (:instructions question))
                         "Which operation addresses the request?"))
      (is (str/includes? (pr-str (:instructions question))
                         "The operation that addresses the request")))))

(deftest boolean-output-is-asked-as-noul
  (let [requests (atom [])]
    (register-decision-provider! :decision-test-noul-request
                                 (response {:answerable {:type "noul" :noul 0.83}})
                                 requests)
    (is (= {:answerable true}
           (llm/predict :decision-test-noul-request boolean-spec {:request "x"})))
    (let [question (let [qs (:questions (first @requests))]
                     (get qs :answerable (get qs "answerable")))]
      (is (= "noul" (name (:type question))))
      (is (= #{"true" "false"}
             (set (map name (keys (:criteria question)))))))))

(deftest runtime-option-identities-round-trip-verbatim
  (let [requests (atom [])
        ids ["rpt/Q3-2026#rev" "rpt/Q2-2026#rev"]
        spec (assoc-in route-spec [:outputs 0 :spec]
                       [:enum {:descriptions {"rpt/Q3-2026#rev" "Q3 revenue report"
                                              "rpt/Q2-2026#rev" "Q2 revenue report"}}
                        "rpt/Q3-2026#rev" "rpt/Q2-2026#rev"])
        probabilities {"rpt/Q3-2026#rev" 0.9 "rpt/Q2-2026#rev" 0.1}]
    (register-decision-provider!
     :decision-test-verbatim
     (response {:route {:type "choice" :choice "rpt/Q3-2026#rev"
                        :confidence (confidence-for probabilities)
                        :probabilities probabilities}})
     requests)
    (is (= {:route "rpt/Q3-2026#rev"}
           (llm/predict :decision-test-verbatim spec {:request "x"})))
    (is (= (set ids)
           (set (map #(if (keyword? %) (subs (str %) 1) %)
                     (keys (:criteria (let [qs (:questions (first @requests))]
                                        (get qs :route (get qs "route")))))))))))

;; ---------------------------------------------------------------------------
;; DecisionAnswersAreValidatedNotRepaired
;; ---------------------------------------------------------------------------

(defn- failure-kind-for [answers spec]
  (let [provider (keyword (str "decision-test-invalid-" (random-uuid)))]
    (register-decision-provider! provider (response answers) (atom []))
    (some-> (predict-failure #(llm/predict provider spec {:request "x"}))
            ex-data
            :failure-kind)))

(deftest invalid-decision-answers-fail-as-schema-invalid
  (doseq [[label answers spec]
          [["unknown option"
            {:route {:type "choice" :choice "delete"}} route-spec]
           ["missing answer" {} route-spec]
           ["wrong primitive"
            {:route {:type "noul" :noul 0.9}} route-spec]
           ["distribution misses an offered option"
            {:route {:type "choice" :choice "lookup"
                     :probabilities {"lookup" 0.8 "research" 0.2}}} route-spec]
           ["distribution does not sum to one"
            {:route {:type "choice" :choice "lookup"
                     :probabilities {"lookup" 0.8 "research" 0.5 "clarify" 0.1}}} route-spec]
           ["selected option is not the most probable"
            {:route {:type "choice" :choice "research"
                     :probabilities lookup-probabilities}} route-spec]
           ["confidence disagrees with the distribution"
            {:route {:type "choice" :choice "lookup" :confidence 0.99
                     :probabilities lookup-probabilities}} route-spec]
           ["non-finite noul" {:answerable {:type "noul" :noul ##NaN}} boolean-spec]
           ["noul out of range" {:answerable {:type "noul" :noul 1.4}} boolean-spec]
           ["undecided noul" {:answerable {:type "noul" :noul 0.5}} boolean-spec]]]
    (testing label
      (is (= :schema-validation-failed (failure-kind-for answers spec))))))

(deftest transport-failure-is-classified-and-never-leaks-the-credential
  (let [provider :decision-test-transport]
    (register-decision-provider!
     provider
     (fn [_] (throw (ex-info (str "HTTP 500 Authorization: Bearer " dummy-key) {:status 500})))
     (atom []))
    (let [e (predict-failure #(llm/predict provider route-spec {:request "x"}))]
      (is (= :transport-failure (:failure-kind (ex-data e))))
      (is (not (str/includes? (pr-str (ex-message e)) dummy-key)))
      (is (not (str/includes? (pr-str (ex-data e)) dummy-key))))))

;; ---------------------------------------------------------------------------
;; DecisionMetadataIsPreserved
;; ---------------------------------------------------------------------------

(deftest metadata-preserves-reported-distribution-model-and-usage
  (register-decision-provider! :decision-test-metadata (response valid-choice) (atom []))
  (let [result (llm/predict :decision-test-metadata route-spec {:request "x"}
                            {:with-metadata? true})
        evidence (get-in result [:decisions :route])]
    (is (= {:route "lookup"} (:outputs result)))
    (is (= "typesafe/jev-1.13-20260917" (:model result)))
    (is (= 120 (get-in result [:usage :prompt_tokens])))
    (is (= 0.0004 (get-in result [:usage :cost])))
    (is (= :choice (:primitive evidence)))
    (is (= lookup-probabilities (:probabilities evidence)))
    (is (= (confidence-for lookup-probabilities) (:confidence evidence)))))

(deftest unreported-confidence-is-absent-not-synthesised
  (register-decision-provider! :decision-test-bare
                               (response {:route {:type "choice" :choice "lookup"}})
                               (atom []))
  (let [evidence (get-in (llm/predict :decision-test-bare route-spec {:request "x"}
                                      {:with-metadata? true})
                         [:decisions :route])]
    (is (not (contains? evidence :confidence)))
    (is (not (contains? evidence :probabilities)))))

(deftest noul-metadata-keeps-its-probability-without-a-confidence
  (register-decision-provider! :decision-test-noul-metadata
                               (response {:answerable {:type "noul" :noul 0.21}})
                               (atom []))
  (let [result (llm/predict :decision-test-noul-metadata boolean-spec {:request "x"}
                            {:with-metadata? true})
        evidence (get-in result [:decisions :answerable])]
    (is (= {:answerable false} (:outputs result)))
    (is (= :noul (:primitive evidence)))
    (is (= 0.21 (:probability evidence)))
    (is (not (contains? evidence :confidence)))))

;; ---------------------------------------------------------------------------
;; Default transport: OpenRouter alpha decisions endpoint
;; ---------------------------------------------------------------------------

(deftest default-transport-posts-to-the-openrouter-decisions-endpoint
  (let [posts (atom [])]
    (llm/register-provider! :decision-test-default-transport
                            {:provider :openrouter
                             :protocol :decision
                             :model "typesafe/jev-1.13"
                             :config {:api-key dummy-key}})
    (with-redefs [http/post (fn [url request]
                              (swap! posts conj {:url url :request request})
                              {:status 200 :body (response valid-choice)})]
      (is (= {:route "lookup"}
             (llm/predict :decision-test-default-transport route-spec {:request "x"}))))
    (is (= "https://openrouter.ai/api/alpha/decisions" (:url (first @posts))))
    (is (= (str "Bearer " dummy-key)
           (get-in (first @posts) [:request :headers "Authorization"])))))

;; ---------------------------------------------------------------------------
;; ProtocolFollowsRegisteredProvider — the streaming call reaches the same
;; decision protocol (it never falls through to a conversational completion)
;; ---------------------------------------------------------------------------

(deftest streaming-call-to-a-decision-provider-uses-the-decision-protocol
  (let [requests (atom [])
        completions (atom 0)]
    (register-decision-provider! :decision-test-stream (response valid-choice) requests)
    (with-redefs [router/completion (fn [& _]
                                     (swap! completions inc)
                                     (doto (clojure.core.async/chan) clojure.core.async/close!))]
      (let [ch (llm/predict-stream-v2 :decision-test-stream route-spec {:request "x"})
            events (loop [acc []]
                     (if-some [e (clojure.core.async/<!! ch)] (recur (conj acc e)) acc))
            terminal (last events)]
        (is (zero? @completions) "no conversational completion is attempted")
        (is (= 1 (count @requests)))
        (is (= [:final] (mapv :orc/event (filter #(#{:final :error} (:orc/event %)) events)))
            "exactly one terminal event")
        (is (= {:route "lookup"} (:outputs terminal)))
        (is (= :choice (get-in terminal [:decisions :route :primitive])))
        (is (= 120 (get-in terminal [:usage :prompt_tokens])))))))

(deftest streaming-decision-failure-is-one-error-terminal
  (register-decision-provider! :decision-test-stream-fail
                               (response {:route {:type "choice" :choice "delete"}})
                               (atom []))
  (with-redefs [router/completion (fn [& _]
                                   (doto (clojure.core.async/chan) clojure.core.async/close!))]
    (let [ch (llm/predict-stream-v2 :decision-test-stream-fail route-spec {:request "x"})
          events (loop [acc []]
                   (if-some [e (clojure.core.async/<!! ch)] (recur (conj acc e)) acc))]
      (is (= [:error] (mapv :orc/event events))))))

;; ---------------------------------------------------------------------------
;; Reported resolution. Real OpenRouter Jev responses (captured verbatim,
;; 2026-10-05) report probabilities and confidence rounded to 0.01, while the
;; confidence is computed from the unrounded distribution. Validation must
;; hold the documented definitions within the reported resolution, and must
;; still reject genuine contradictions.
;; ---------------------------------------------------------------------------

(def ^:private captured-lookup
  {:route {:type "choice" :choice "lookup"
           :probabilities {"lookup" 0.98 "clarify" 0.02 "research" 0}
           :confidence 0.96}})

(def ^:private captured-two-option
  {:q {:type "choice" :choice "no"
       :probabilities {"yes" 0.06 "no" 0.94}
       :confidence 0.89}})

(def ^:private yes-no-spec
  {:inputs [{:name :request :spec :string :description "The question"}]
   :outputs [{:name :q :spec [:enum {:descriptions {"yes" "It is raining"
                                                    "no" "It is not raining"}}
                              "yes" "no"]
              :description "Pick"}]
   :instructions "Pick"})

(deftest real-rounded-responses-are-accepted
  (doseq [[label answers spec expected]
          [["three options, confidence off by rounding" captured-lookup route-spec {:route "lookup"}]
           ["two options, confidence off by rounding" captured-two-option yes-no-spec {:q "no"}]]]
    (testing label
      (let [provider (keyword (str "decision-test-rounded-" (random-uuid)))]
        (register-decision-provider! provider (response answers) (atom []))
        (is (= expected (llm/predict provider spec {:request "x"})))))))

(deftest rounded-tie-at-the-maximum-accepts-either-maximal-choice
  (let [provider :decision-test-rounded-tie]
    (register-decision-provider!
     provider
     (response {:q {:type "choice" :choice "yes"
                    :probabilities {"yes" 0.5 "no" 0.5} :confidence 0}})
     (atom []))
    (is (= {:q "yes"} (llm/predict provider yes-no-spec {:request "x"})))))

(deftest contradictions-beyond-the-resolution-still-fail
  (doseq [[label answers spec]
          [["confidence far from the distribution"
            {:q {:type "choice" :choice "no" :probabilities {"yes" 0.06 "no" 0.94}
                 :confidence 0.5}} yes-no-spec]
           ["sum off by more than rounding"
            {:q {:type "choice" :choice "no" :probabilities {"yes" 0.1 "no" 0.94}}}
            yes-no-spec]
           ["selected option clearly not the most probable"
            {:q {:type "choice" :choice "yes" :probabilities {"yes" 0.4 "no" 0.6}}}
            yes-no-spec]]]
    (testing label
      (is (= :schema-validation-failed (failure-kind-for answers spec))))))
