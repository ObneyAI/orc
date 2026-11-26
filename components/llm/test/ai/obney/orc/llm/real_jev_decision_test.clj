(ns ai.obney.orc.llm.real-jev-decision-test
  "REAL Jev (TypeSafe) decisions through OpenRouter's /api/alpha/decisions via
   the ordinary `llm/predict` / `predict-stream-v2` calls. Opt-in through the
   shared ORC_OPENROUTER_E2E_TESTS gate; uses OPENROUTER_API_KEY (never printed).

   Assertions are on protocol behaviour and on clear-cut propositions only;
   they make no accuracy or calibration claim."
  (:require [clojure.core.async :as async]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [ai.obney.orc.llm.interface :as llm]))

(def jev-model "typesafe/jev-1.13")

(defn- live? []
  (and (= "true" (some-> (System/getenv "ORC_OPENROUTER_E2E_TESTS") str/trim str/lower-case))
       (not (str/blank? (System/getenv "OPENROUTER_API_KEY")))))

(defmacro with-live [& body]
  `(if (live?)
     (do ~@body)
     (testing "REAL-JEV skipped: gate or key absent" (is true))))

(defn- register! []
  (llm/register-provider! :real-jev
                          {:provider :openrouter
                           :protocol :decision
                           :model jev-model
                           :config {:api-key (System/getenv "OPENROUTER_API_KEY")}}))

(def ^:private route-descriptions
  {"lookup" "Retrieve a figure that already exists in a stored report."
   "research" "Investigate an open question that needs new evidence gathered."
   "clarify" "The request is too ambiguous to act on; ask the user what they mean."})

(def ^:private route-spec
  {:inputs [{:name :request :spec :string :description "The user's request"}]
   :outputs [{:name :route
              :spec (into [:enum {:descriptions route-descriptions}] (keys route-descriptions))
              :description "The operation that best addresses the request"}]
   :instructions "Choose the operation that addresses the user's request."})

(def ^:private truth-spec
  {:inputs [{:name :claim :spec :string :description "A factual claim"}]
   :outputs [{:name :holds :spec :boolean :description "The claim is factually true"}]
   :instructions "Decide whether the claim is factually true."})

(deftest real-jev-choice-returns-a-validated-offered-option
  (with-live
    (register!)
    (let [result (llm/predict :real-jev route-spec
                              {:request "Please pull the Q3 2025 revenue number from the board report we filed."}
                              {:with-metadata? true :timeout-ms 30000})
          evidence (get-in result [:decisions :route])]
      (println :REAL-JEV-CHOICE (pr-str (dissoc result :raw-response)))
      (is (contains? route-descriptions (get-in result [:outputs :route])))
      (is (= :choice (:primitive evidence)))
      (is (string? (:model result)))
      (is (pos? (or (get-in result [:usage :prompt_tokens]) 0)))
      (when (contains? evidence :probabilities)
        (is (= (set (keys route-descriptions)) (set (keys (:probabilities evidence)))))))))

(deftest real-jev-noul-decides-clear-cut-propositions
  (with-live
    (register!)
    (let [t (llm/predict :real-jev truth-spec {:claim "Two plus two equals four."}
                         {:with-metadata? true :timeout-ms 30000})
          f (llm/predict :real-jev truth-spec {:claim "Two plus two equals five."}
                         {:with-metadata? true :timeout-ms 30000})]
      (println :REAL-JEV-NOUL-TRUE (pr-str (:decisions t)) :FALSE (pr-str (:decisions f)))
      (is (= {:holds true} (:outputs t)))
      (is (= {:holds false} (:outputs f)))
      (is (= :noul (get-in t [:decisions :holds :primitive])))
      (is (number? (get-in t [:decisions :holds :probability])))
      (is (not (contains? (get-in t [:decisions :holds]) :confidence))))))

(deftest real-jev-runtime-identities-round-trip-verbatim
  (with-live
    (register!)
    (let [ids {"rpt/Q3-2025#rev" "Third-quarter 2025 revenue report"
               "rpt/Q2-2025#rev" "Second-quarter 2025 revenue report"
               "hr/2025#headcount" "2025 headcount summary"}
          spec (assoc-in route-spec [:outputs 0 :spec]
                         (into [:enum {:descriptions ids}] (keys ids)))
          result (llm/predict :real-jev spec
                              {:request "I need the third quarter 2025 revenue report."}
                              {:with-metadata? true :timeout-ms 30000})]
      (println :REAL-JEV-IDS (pr-str (:outputs result)) (pr-str (get-in result [:decisions :route :probabilities])))
      (is (contains? ids (get-in result [:outputs :route]))))))

(deftest real-jev-streaming-reaches-the-decision-protocol
  (with-live
    (register!)
    (let [ch (llm/predict-stream-v2 :real-jev truth-spec {:claim "Water is wet."}
                                    {:timeout-ms 30000})
          events (loop [acc []] (if-some [e (async/<!! ch)] (recur (conj acc e)) acc))]
      (println :REAL-JEV-STREAM (pr-str (mapv #(dissoc % :raw-response) events)))
      (is (= [:final] (mapv :orc/event events)))
      (is (boolean? (get-in (last events) [:outputs :holds]))))))

(def ^:private battery
  "Varied requests and option-set sizes; every real response must pass the
   protocol validator (no accuracy claim)."
  [[{"approve" "Approve the expense as submitted."
     "reject" "Reject the expense."} "Lunch with a client, $40, receipt attached."]
   [{"billing" "Charges, invoices, refunds."
     "technical" "Errors, outages, bugs."
     "account" "Login, password, profile."
     "sales" "Pricing questions before purchase."
     "other" "None of the above."} "I was charged twice this month."]
   [{"en" "English" "fr" "French" "de" "German" "es" "Spanish"
     "it" "Italian" "pt" "Portuguese" "nl" "Dutch" "unknown" "Cannot tell"}
    "Ou est la gare, s'il vous plait ?"]
   [{"low" "Minor inconvenience." "medium" "Degraded service for some users."
     "high" "Outage for many users." "critical" "Data loss or security breach."}
    "Customer database exposed publicly for 3 hours."]
   [{"lookup" "Retrieve an existing figure." "research" "Investigate a new question."
     "clarify" "Too ambiguous; ask."} "hmm"]
   [{"ticket/8812" "Login fails on Safari 17"
     "ticket/8813" "Invoice PDF renders blank"
     "ticket/9001" "Dark mode colours wrong"
     "none" "No listed ticket matches"} "The invoice download shows an empty page."]])

(deftest real-jev-choice-battery-validates
  (with-live
    (register!)
    (doseq [[options request] battery]
      (let [spec {:inputs [{:name :request :spec :string :description "The input"}]
                  :outputs [{:name :pick
                             :spec (into [:enum {:descriptions options}] (keys options))
                             :description "The best matching option"}]
                  :instructions "Choose the option that best fits the input."}
            result (try (llm/predict :real-jev spec {:request request}
                                     {:with-metadata? true :timeout-ms 30000})
                        (catch Exception e {:error (ex-message e)}))]
        (println :REAL-JEV-BATTERY (count options) (pr-str request)
                 (pr-str (or (:error result)
                             [(get-in result [:outputs :pick])
                              (get-in result [:decisions :pick :probabilities])
                              (get-in result [:decisions :pick :confidence])])))
        (testing (str (count options) " options: " request)
          (is (nil? (:error result)))
          (is (contains? options (get-in result [:outputs :pick]))))))))
