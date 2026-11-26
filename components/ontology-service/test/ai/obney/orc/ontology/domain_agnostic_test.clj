(ns ai.obney.orc.ontology.domain-agnostic-test
  "Tests to verify self-learning features work across any domain.

   These tests prove the ontology component is domain-agnostic and works
   for legal, sales, construction, and other domains - not just drones.

   Uses direct read model projection testing (like core_test.clj) to verify
   the domain-agnostic field handling works correctly."
  (:require [ai.obney.orc.ontology.core.read-models :as read-models]
            [ai.obney.orc.ontology.core.logic :as logic]
            [clojure.test :refer [deftest testing is]]))

;; =============================================================================
;; Legal Domain Tests
;; =============================================================================

(deftest legal-domain-strength-projection-test
  (testing "Read model preserves legal-review domain fields"
    (let [tree-id (random-uuid)
          events [{:event/type :ontology/tree-strength-recorded
                   :tree-id tree-id
                   :pattern-uri "success:ContractApproved"
                   :confidence 0.92
                   :evidence-trace-ids [(random-uuid)]
                   :avg-score 0.88
                   :recorded-at "2024-01-01T00:00:00Z"
                   :domain-type "legal-review"
                   :context-conditions {:contract-value 1500000
                                        :has-indemnification true
                                        :clause-count 47
                                        :risk-score 0.3}
                   :action-taken {:type "approve"
                                  :reviewer "senior-partner"
                                  :reason "Standard terms"}
                   :expected-outcome "contract approved"}]
          result (logic/tree-profiles {} events)
          profile (get result tree-id)]
      (is (some? profile) "Should have profile")
      (is (= 1 (count (:strengths profile))) "Should have 1 strength")
      (let [strength (first (:strengths profile))]
        (is (= "legal-review" (:domain-type strength)) "Should preserve domain-type")
        (is (= {:contract-value 1500000
                :has-indemnification true
                :clause-count 47
                :risk-score 0.3}
               (:context-conditions strength))
            "Should preserve context-conditions")
        (is (= {:type "approve"
                :reviewer "senior-partner"
                :reason "Standard terms"}
               (:action-taken strength))
            "Should preserve action-taken")
        (is (= "contract approved" (:expected-outcome strength))
            "Should preserve expected-outcome")))))

(deftest legal-domain-weakness-projection-test
  (testing "Read model preserves legal domain weakness fields"
    (let [tree-id (random-uuid)
          events [{:event/type :ontology/tree-weakness-recorded
                   :tree-id tree-id
                   :failure-uri "failure:MissedClause"
                   :frequency 0.25
                   :severity :high
                   :triggers ["long contract" "unusual terms"]
                   :evidence-trace-ids [(random-uuid)]
                   :recorded-at "2024-01-01T00:00:00Z"
                   :domain-type "legal-review"
                   :failure-context {:contract-length 150
                                     :complexity-score 0.8}}]
          result (logic/tree-profiles {} events)
          profile (get result tree-id)]
      (is (some? profile))
      (is (= 1 (count (:weaknesses profile))))
      (let [weakness (first (:weaknesses profile))]
        (is (= "legal-review" (:domain-type weakness)))
        (is (= {:contract-length 150 :complexity-score 0.8}
               (:failure-context weakness)))))))

;; =============================================================================
;; Sales Domain Tests
;; =============================================================================

(deftest sales-domain-strength-projection-test
  (testing "Read model preserves sales-outreach domain fields"
    (let [tree-id (random-uuid)
          events [{:event/type :ontology/tree-strength-recorded
                   :tree-id tree-id
                   :pattern-uri "success:MeetingScheduled"
                   :confidence 0.85
                   :evidence-trace-ids [(random-uuid)]
                   :avg-score 0.82
                   :recorded-at "2024-01-01T00:00:00Z"
                   :domain-type "sales-outreach"
                   :context-conditions {:lead-score 85
                                        :days-since-contact 3
                                        :company-size "enterprise"
                                        :decision-maker? true}
                   :action-taken {:type "send-email"
                                  :template "personalized-followup"}}]
          result (logic/tree-profiles {} events)
          profile (get result tree-id)]
      (is (some? profile))
      (let [strength (first (:strengths profile))]
        (is (= "sales-outreach" (:domain-type strength)))
        (is (= 85 (get-in strength [:context-conditions :lead-score])))
        (is (= "enterprise" (get-in strength [:context-conditions :company-size])))
        (is (true? (get-in strength [:context-conditions :decision-maker?])))
        (is (= "send-email" (get-in strength [:action-taken :type])))))))

;; =============================================================================
;; Construction Domain Tests
;; =============================================================================

(deftest construction-domain-strength-projection-test
  (testing "Read model preserves construction-scheduling domain fields"
    (let [tree-id (random-uuid)
          events [{:event/type :ontology/tree-strength-recorded
                   :tree-id tree-id
                   :pattern-uri "success:PhaseCompleted"
                   :confidence 0.88
                   :evidence-trace-ids [(random-uuid)]
                   :avg-score 0.85
                   :recorded-at "2024-01-01T00:00:00Z"
                   :domain-type "construction-scheduling"
                   :context-conditions {:weather-risk 0.2
                                        :crew-availability 0.9
                                        :materials-ready true
                                        :permits-approved true}
                   :action-taken {:type "proceed"
                                  :contingency "indoor-work-backup"}}]
          result (logic/tree-profiles {} events)
          profile (get result tree-id)]
      (is (some? profile))
      (let [strength (first (:strengths profile))]
        (is (= "construction-scheduling" (:domain-type strength)))
        (is (= 0.2 (get-in strength [:context-conditions :weather-risk])))
        (is (true? (get-in strength [:context-conditions :materials-ready])))))))

;; =============================================================================
;; Multi-Domain Accumulation Tests
;; =============================================================================

(deftest multi-domain-accumulation-projection-test
  (testing "Single tree can accumulate patterns from multiple domains"
    (let [tree-id (random-uuid)
          events [{:event/type :ontology/tree-strength-recorded
                   :tree-id tree-id
                   :pattern-uri "success:LegalApproval"
                   :confidence 0.9
                   :evidence-trace-ids [(random-uuid)]
                   :avg-score 0.88
                   :recorded-at "2024-01-01T00:00:00Z"
                   :domain-type "legal-review"
                   :context-conditions {:contract-value 1000000}}
                  {:event/type :ontology/tree-strength-recorded
                   :tree-id tree-id
                   :pattern-uri "success:SalesClose"
                   :confidence 0.85
                   :evidence-trace-ids [(random-uuid)]
                   :avg-score 0.82
                   :recorded-at "2024-01-01T00:00:01Z"
                   :domain-type "sales-outreach"
                   :context-conditions {:lead-score 90}}
                  {:event/type :ontology/tree-strength-recorded
                   :tree-id tree-id
                   :pattern-uri "success:TaskCompleted"
                   :confidence 0.92
                   :evidence-trace-ids [(random-uuid)]
                   :avg-score 0.90
                   :recorded-at "2024-01-01T00:00:02Z"
                   :domain-type "project-management"
                   :context-conditions {:deadline-days 5}}]
          result (logic/tree-profiles {} events)
          profile (get result tree-id)]
      (is (= 3 (count (:strengths profile))) "Should have 3 strengths")
      (let [domains (set (map :domain-type (:strengths profile)))]
        (is (= #{"legal-review" "sales-outreach" "project-management"} domains)
            "Should have all three domain types")))))

;; =============================================================================
;; Backward Compatibility Tests
;; =============================================================================

(deftest backward-compatibility-state-conditions-test
  (testing "Old field name 'state-conditions' is converted to context-conditions"
    (let [tree-id (random-uuid)
          events [{:event/type :ontology/tree-strength-recorded
                   :tree-id tree-id
                   :pattern-uri "success:LegacyPattern"
                   :confidence 0.85
                   :evidence-trace-ids [(random-uuid)]
                   :avg-score 0.82
                   :recorded-at "2024-01-01T00:00:00Z"
                   :state-conditions {:velocity 0.5 :battery 80}}]
          result (logic/tree-profiles {} events)
          profile (get result tree-id)]
      (is (some? profile))
      (let [strength (first (:strengths profile))]
        (is (some? (:context-conditions strength))
            "Should have context-conditions from state-conditions")
        (is (= 0.5 (get-in strength [:context-conditions :velocity])))))))

;; =============================================================================
;; Flexible Schema Tests
;; =============================================================================

(deftest flexible-context-conditions-test
  (testing "Context conditions accept any key-value pairs"
    (let [tree-id (random-uuid)
          events [{:event/type :ontology/tree-strength-recorded
                   :tree-id tree-id
                   :pattern-uri "success:FlexiblePattern"
                   :confidence 0.9
                   :evidence-trace-ids [(random-uuid)]
                   :avg-score 0.88
                   :recorded-at "2024-01-01T00:00:00Z"
                   :domain-type "custom-domain"
                   :context-conditions {:foo "bar"
                                        :baz 123
                                        :nested {:a 1 :b 2}
                                        :list-value [1 2 3]
                                        :boolean-flag true}}]
          result (logic/tree-profiles {} events)
          strength (-> result (get tree-id) :strengths first)]
      (is (= "bar" (get-in strength [:context-conditions :foo])))
      (is (= 123 (get-in strength [:context-conditions :baz])))
      (is (= {:a 1 :b 2} (get-in strength [:context-conditions :nested])))
      (is (= [1 2 3] (get-in strength [:context-conditions :list-value])))
      (is (true? (get-in strength [:context-conditions :boolean-flag]))))))

(deftest flexible-action-taken-test
  (testing "Action taken accepts any structure"
    (let [tree-id (random-uuid)
          events [{:event/type :ontology/tree-strength-recorded
                   :tree-id tree-id
                   :pattern-uri "success:FlexibleAction"
                   :confidence 0.9
                   :evidence-trace-ids [(random-uuid)]
                   :avg-score 0.88
                   :recorded-at "2024-01-01T00:00:00Z"
                   :domain-type "custom-domain"
                   :context-conditions {:input "test"}
                   :action-taken {:type "complex-action"
                                  :sub-actions [{:step 1 :do "a"}
                                                {:step 2 :do "b"}]
                                  :metadata {:source "test" :version 2}}}]
          result (logic/tree-profiles {} events)
          strength (-> result (get tree-id) :strengths first)]
      (is (= "complex-action" (get-in strength [:action-taken :type])))
      (is (= 2 (count (get-in strength [:action-taken :sub-actions]))))
      (is (= 2 (get-in strength [:action-taken :metadata :version]))))))

;; =============================================================================
;; Evidence Count Test
;; =============================================================================

(deftest evidence-count-preserved-test
  (testing "Evidence count is calculated from evidence-trace-ids"
    (let [tree-id (random-uuid)
          trace-ids [(random-uuid) (random-uuid) (random-uuid)]
          events [{:event/type :ontology/tree-strength-recorded
                   :tree-id tree-id
                   :pattern-uri "success:Pattern"
                   :confidence 0.9
                   :evidence-trace-ids trace-ids
                   :avg-score 0.88
                   :recorded-at "2024-01-01T00:00:00Z"}]
          result (logic/tree-profiles {} events)
          strength (-> result (get tree-id) :strengths first)]
      (is (= 3 (:evidence-count strength)) "Should count evidence traces"))))
