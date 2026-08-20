(ns ai.obney.orc.orc-service.real-llm-repl-researcher-output-contract-e2e-test
  "DET-E2E-158: a real Phase-1 model uses the disclosed write schema."
  (:require [clojure.test :refer [deftest is testing]]
            [malli.core :as m]
            [ai.obney.orc.orc-service.complex-e2e-support :as live]
            [ai.obney.orc.orc-service.interface :as sheet]
            [ai.obney.orc.orc-service.test-helpers :as h]))

(def ^:private draft-schema
  [:map {:closed true}
   [:explanation [:string {:min 1 :max 4000}]]])

(deftest det-e2e-158-real-phase1-uses-declared-structured-output-schema
  (testing "semantic-only consumer instructions produce a conforming structured final value"
    (live/with-real-openrouter
      (live/register-openrouter!)
      (h/with-async-test-context
        [ctx {:context {:llm-provider :openrouter :model live/openrouter-model}}]
        (let [definition
              (sheet/workflow "det-e2e-158-schema-guided-finalization"
                (sheet/blackboard {:question :string
                                   :research-draft draft-schema})
                (sheet/repl-researcher "schema-guided-live-researcher"
                  :model live/openrouter-model
                  :instruction (str "Assess the question carefully and provide a concise, "
                                    "well-reasoned result grounded in the supplied context.")
                  :reads [:question]
                  :writes [:research-draft]
                  :rlm {:recursive? false}))
              sheet-id (sheet/build-workflow! ctx definition)
              result (sheet/execute ctx sheet-id
                                    {:question "Why do explicit data contracts improve reliable software handoffs?"}
                                    :timeout-ms 180000)
              draft (get-in result [:outputs :research-draft])]
          (is (= :success (:status result)) (pr-str result))
          (is (m/validate draft-schema draft)
              (str "real Phase-1 finalization must satisfy the disclosed schema: "
                   (pr-str draft)))
          (is (seq (:explanation draft)))
          (is (not-any? #(and (= :sheet/execution-value-rejected (:event/type %))
                              (= :research-draft (:key %)))
                        (live/events ctx))
              "the conforming final value crosses no rejection boundary")
          (live/assert-live-provenance! ctx (:trace-id result)))))))
