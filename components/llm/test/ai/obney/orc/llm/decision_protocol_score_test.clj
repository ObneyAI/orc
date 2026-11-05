(ns ai.obney.orc.llm.decision-protocol-score-test
  "Contract DecisionProtocolPrediction (specs/llm.allium), ordered bands: an
   ordered band is asked as a score over its described levels; the selected
   band is the uniquely most probable level; an exact tie selects no band
   (stable kind :undecided); the reported expected position is kept as
   reported and never rounded into a band. Through the public `llm/predict`
   boundary with an injected transport."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.obney.orc.llm.interface :as llm]))

(def ^:private descriptions {"1" "Unsupported facts" "2" "Partly supported facts" "3" "All facts supported"})

(def ^:private band-spec
  {:inputs [{:name :evidence :spec :string :description "Evidence"}]
   :outputs [{:name :band
              :spec [:enum {:ordered-bands true :descriptions descriptions} "1" "2" "3"]
              :description "The band that applies"}]
   :instructions "Grade the evidence."})

(def ^:private legend {"0" "Unsupported facts" "1" "Partly supported facts" "2" "All facts supported"})

(defn- register! [reply requests]
  (llm/register-provider!
   :score-jev
   {:provider :openrouter :protocol :decision :model "typesafe/jev-1.13"
    :config {:api-key "dummy-not-a-key"
             :decision-transport (fn [request] (swap! requests conj request) reply)}}))

(defn- answer [m]
  {:id "dec-1" :model "typesafe/jev-1.13-20260917" :provider "TypeSafe"
   :answers {:band (merge {:type "score" :legend legend} m)}
   :usage {:input_tokens 309 :output_tokens 17 :cost 1.2978E-5}})

(defn- predict [reply]
  (let [requests (atom [])]
    (register! reply requests)
    {:requests requests
     :result (try (llm/predict :score-jev band-spec {:evidence "e"}
                               {:with-metadata? true :with-provider-evidence? true})
                  (catch clojure.lang.ExceptionInfo e e))}))

(deftest score-request-carries-ordered-criteria-and-the-answer-is-the-unique-argmax
  (let [{:keys [requests result]}
        (predict (answer {:score 1.18 :confidence 0
                          :probabilities {"0" 0.41 "1" 0.01 "2" 0.58}}))
        question (get-in (first @requests) [:questions "band"])]
    (is (= "score" (:type question)))
    (is (= ["Unsupported facts" "Partly supported facts" "All facts supported"]
           (:criteria question)))
    (is (= "3" (get-in result [:outputs :band])))
    (is (= {:primitive :score
            :probabilities {"0" 0.41 "1" 0.01 "2" 0.58}
            :expected-position 1.18
            :confidence 0}
           (dissoc (get-in result [:decisions :band]) :answer))
        "expected position and confidence are kept as reported")))

(deftest exact-tie-is-undecided-and-carries-the-distribution
  (let [{:keys [result]} (predict (answer {:score 1.0 :probabilities {"0" 0.45 "1" 0.1 "2" 0.45}}))]
    (is (instance? clojure.lang.ExceptionInfo result))
    (is (= :undecided (:failure-kind (ex-data result))))
    (is (= {:probabilities {"0" 0.45 "1" 0.1 "2" 0.45} :expected-position 1.0}
           (get-in (ex-data result) [:provider-evidence :band-distribution])))))

(deftest malformed-score-answers-are-schema-invalid
  (doseq [[label m] {"legend mismatch" {:score 1.0 :probabilities {"0" 0.2 "1" 0.5 "2" 0.3}
                                        :legend {"0" "a" "1" "b" "2" "c"}}
                     "missing level" {:score 1.0 :probabilities {"0" 0.4 "2" 0.6}}
                     "no probabilities" {:score 1.0}
                     "mass far from one" {:score 1.0 :probabilities {"0" 0.2 "1" 0.2 "2" 0.2}}
                     "position out of range" {:score 3.5 :probabilities {"0" 0.2 "1" 0.5 "2" 0.3}}
                     "NaN probability" {:score 1.0 :probabilities {"0" Double/NaN "1" 0.5 "2" 0.3}}}]
    (testing label
      (let [{:keys [result]} (predict (answer m))]
        (is (= :schema-validation-failed (:failure-kind (ex-data result))) (pr-str result))))))

(deftest quantized-mass-is-accepted
  (let [{:keys [result]} (predict (answer {:score 1.58 :probabilities {"0" 0.0 "1" 0.41 "2" 0.58}}))]
    (is (= "3" (get-in result [:outputs :band])))))
