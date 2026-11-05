(ns ai.obney.orc.evaluation.real-llm-judges-e2e-test
  "REAL-model proof that a built-in judge is a behaviour on the one run path:
   a feedback-form grounding judge on a conversational model attached to a real
   llm leaf, and a score-only grounding judge on a native decision model (Jev).
   Opt-in through ORC_OPENROUTER_E2E_TESTS; uses OPENROUTER_API_KEY (never
   printed). Clear-cut evidence only; no accuracy or calibration claim."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.time.interface :as time]
            [ai.obney.orc.evaluation.interface]
            [ai.obney.orc.evaluation.core.judge-runtime]
            [ai.obney.orc.llm.interface :as llm]
            [ai.obney.orc.ontology.interface]
            [ai.obney.orc.ontology.interface.schemas]
            [ai.obney.orc.orc-service.interface :as sheet]
            [ai.obney.orc.orc-service.interface.schemas]
            [ai.obney.orc.orc-service.test-helpers :as h]
            [litellm.router :as router]))

(def ^:private chat-model "z-ai/glm-5.3-flash")

(defn- live? []
  (and (= "true" (some-> (System/getenv "ORC_OPENROUTER_E2E_TESTS") str/trim str/lower-case))
       (not (str/blank? (System/getenv "OPENROUTER_API_KEY")))))

(defmacro ^:private with-live [& body]
  `(if (live?)
     (do ~@body)
     (testing "REAL-LLM skipped: gate or key absent" (is true))))

(defn- register-providers! []
  (router/register! :openrouter
                    {:provider :openrouter :model chat-model
                     :config {:api-base "https://openrouter.ai/api/v1"
                              :api-key (System/getenv "OPENROUTER_API_KEY")}})
  (llm/register-provider! :live-jev
                          {:provider :openrouter :protocol :decision :model "typesafe/jev-1.13"
                           :config {:api-key (System/getenv "OPENROUTER_API_KEY")}}))

(defn- enable-living-description! [ctx]
  (h/run-and-apply! ctx {:command/name :ontology/set-living-description-enabled
                         :command/id (random-uuid)
                         :command/timestamp (time/now)
                         :enabled? true})
  (Thread/sleep 100))

(def ^:private rubric-bands
  {1 "The category contradicts or ignores the ticket"
   2 "The category is only partly supported by the ticket"
   3 "The category is fully supported by the ticket"})

(defn- host-workflow [workflow-name judge-config]
  (sheet/workflow workflow-name
    (sheet/blackboard {:ticket-message :string :category :string})
    (sheet/judges {:the-judge judge-config})
    (sheet/llm "classify"
      :instruction "Classify the support ticket into exactly one category: billing, shipping or technical."
      :reads [:ticket-message]
      :writes [:category]
      :judges ["the-judge"])))

(defn- events [ctx t]
  (into [] (es/read (:event-store ctx) {:types #{t} :tenant-id (:tenant-id ctx)})))

(defn- judge-completions [ctx host-tick-id]
  (->> (events ctx :sheet/node-execution-completed)
       (filter #(and (not= host-tick-id (:tick-id %)) (:model %)))))

(deftest real-feedback-form-grounding-judge
  (with-live
    (register-providers!)
    (h/with-async-test-context [ctx]
      (enable-living-description! ctx)
      (let [sheet-id (sheet/build-workflow!
                      ctx (host-workflow "live-feedback-judge" {:type :grounding}))
            result (sheet/execute ctx sheet-id
                                  {:ticket-message "My invoice shows a double charge for March."}
                                  :timeout-ms 120000)]
        (is (= :success (:status result)) (pr-str (select-keys result [:status :error])))
        (is (h/settle-until! #(seq (events ctx :judge/score-emitted)) :timeout-ms 120000))
        (let [score (first (events ctx :judge/score-emitted))
              completion (first (judge-completions ctx (:trace-id result)))]
          (println :LIVE-FEEDBACK-JUDGE
                   (pr-str {:host-category (get-in result [:outputs :category])
                            :score (:score score)
                            :feedback (:feedback score)
                            :requested-model (:requested-model completion)
                            :resolved-model (:resolved-model completion)
                            :usage (:usage completion)}))
          (is (some? score) "a real scored verdict landed through the behaviour")
          (is (not (str/blank? (:feedback score))))
          (is (some? (:resolved-model completion)))
          (is (pos? (or (get-in completion [:usage :total-tokens]) 0))))))))

(deftest real-score-only-grounding-judge-on-jev
  (with-live
    (register-providers!)
    (h/with-async-test-context [ctx]
      (enable-living-description! ctx)
      (let [judge {:type :grounding :model "live-jev"
                   :rubric {:criterion "Is the category supported by the ticket text?"
                            :stance "Be exacting."
                            :bands rubric-bands
                            :feedback :none}}
            sheet-id (sheet/build-workflow! ctx (host-workflow "live-score-only-judge" judge))
            result (sheet/execute ctx sheet-id
                                  {:ticket-message "My invoice shows a double charge for March."}
                                  :timeout-ms 120000)]
        (is (= :success (:status result)) (pr-str (select-keys result [:status :error])))
        (is (h/settle-until! #(seq (judge-completions ctx (:trace-id result))) :timeout-ms 120000))
        (Thread/sleep 1000)
        (let [completion (first (judge-completions ctx (:trace-id result)))]
          (println :LIVE-SCORE-ONLY-JUDGE
                   (pr-str {:host-category (get-in result [:outputs :category])
                            :decision (:decision completion)
                            :resolved-model (:resolved-model completion)
                            :usage (:usage completion)
                            :legacy-score-events (count (events ctx :judge/score-emitted))}))
          (is (some? (:decision completion)) "the judge ran as a banded decision")
          (is (some? (get-in completion [:decision :answer])))
          (is (empty? (events ctx :judge/score-emitted))
              "a score-only judge records no legacy score event (no feedback to invent)"))))))
